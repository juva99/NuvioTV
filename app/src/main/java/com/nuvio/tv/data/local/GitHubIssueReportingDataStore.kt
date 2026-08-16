package com.nuvio.tv.data.local

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.inject.Inject
import javax.inject.Singleton

private val Context.githubIssueReportingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "github_issue_reporting"
)

data class GitHubIssueReportingSettings(
    val enabled: Boolean = false,
    val tokenConfigured: Boolean = false
)

@Singleton
class GitHubIssueReportingDataStore @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val dataStore = context.githubIssueReportingDataStore
    private val tokenStore = EncryptedGitHubTokenStore(context)

    private val enabledKey = booleanPreferencesKey("enabled")
    private val tokenConfiguredKey = booleanPreferencesKey("token_configured")
    private val lastFingerprintKey = stringPreferencesKey("last_reported_fingerprint")
    private val lastReportedAtKey = longPreferencesKey("last_reported_at_ms")
    private val lastIssueUrlKey = stringPreferencesKey("last_issue_url")

    val settings: Flow<GitHubIssueReportingSettings> = dataStore.data.map { preferences ->
        GitHubIssueReportingSettings(
            enabled = preferences[enabledKey] ?: false,
            tokenConfigured = preferences[tokenConfiguredKey] ?: false
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[enabledKey] = enabled
        }
    }

    suspend fun setToken(token: String) {
        val normalized = token.trim()
        if (normalized.isBlank()) {
            clearToken()
            return
        }

        tokenStore.write(normalized)
        dataStore.edit { preferences ->
            preferences[tokenConfiguredKey] = true
        }
    }

    suspend fun clearToken() {
        tokenStore.clear()
        dataStore.edit { preferences ->
            preferences[tokenConfiguredKey] = false
            preferences[enabledKey] = false
        }
    }

    fun readToken(): String? = tokenStore.read()

    suspend fun wasRecentlyReported(fingerprint: String, nowMs: Long): Boolean {
        val preferences = dataStore.data.first()
        val lastFingerprint = preferences[lastFingerprintKey] ?: return false
        val lastReportedAt = preferences[lastReportedAtKey] ?: return false
        return lastFingerprint == fingerprint &&
            nowMs >= lastReportedAt &&
            nowMs - lastReportedAt < DUPLICATE_WINDOW_MS
    }

    suspend fun lastIssueUrl(): String? = dataStore.data.first()[lastIssueUrlKey]

    suspend fun markReported(fingerprint: String, issueUrl: String, nowMs: Long) {
        dataStore.edit { preferences ->
            preferences[lastFingerprintKey] = fingerprint
            preferences[lastReportedAtKey] = nowMs
            preferences[lastIssueUrlKey] = issueUrl
        }
    }

    companion object {
        const val DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1_000L
    }
}

private class EncryptedGitHubTokenStore(
    context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
        load(null)
    }

    fun write(token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val editor = preferences.edit()
            .putString(CIPHERTEXT_KEY, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        check(editor.commit()) { "Unable to persist GitHub issue token" }
    }

    fun read(): String? {
        val encodedCiphertext = preferences.getString(CIPHERTEXT_KEY, null)
        val encodedIv = preferences.getString(IV_KEY, null)
        if (encodedCiphertext == null && encodedIv == null) return null
        if (encodedCiphertext == null || encodedIv == null) {
            error("Stored GitHub issue token is incomplete")
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(encodedIv, Base64.NO_WRAP))
        )
        return String(
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)),
            StandardCharsets.UTF_8
        )
    }

    fun clear() {
        val editor = preferences.edit()
            .remove(CIPHERTEXT_KEY)
            .remove(IV_KEY)
        check(editor.commit()) { "Unable to clear GitHub issue token" }
    }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing != null) {
            return existing as? SecretKey
                ?: error("Stored GitHub issue token key has an unexpected type")
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES_NAME = "github_issue_reporting_token"
        const val CIPHERTEXT_KEY = "ciphertext"
        const val IV_KEY = "iv"
        const val KEY_ALIAS = "nuvio.github.issue.token"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val AES_KEY_SIZE_BITS = 256
    }
}
