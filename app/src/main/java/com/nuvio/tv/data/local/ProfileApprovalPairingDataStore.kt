package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.core.profileapproval.NtfyProfileApprovalPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.profileApprovalPairingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "profile_approval_pairing",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
        androidx.datastore.preferences.core.emptyPreferences()
    }
)

@Singleton
class ProfileApprovalPairingDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.profileApprovalPairingDataStore
    private val topicsKey = stringPreferencesKey("profile_topics")
    private val mutex = Mutex()

    val topics: Flow<Map<Int, String>> = dataStore.data.map { prefs ->
        decode(prefs[topicsKey])
    }

    suspend fun topicFor(profileId: Int): String? = mutex.withLock {
        topics.first()[profileId]
    }

    suspend fun ensureTopic(profileId: Int): String = mutex.withLock {
        var result: String? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[topicsKey]).toMutableMap()
            result = current[profileId] ?: NtfyProfileApprovalPolicy.generateProfileTopic()
            current[profileId] = result!!
            prefs[topicsKey] = encode(current)
        }
        result!!
    }

    suspend fun regenerateTopic(profileId: Int): String = mutex.withLock {
        var result: String? = null
        dataStore.edit { prefs ->
            val current = decode(prefs[topicsKey]).toMutableMap()
            result = NtfyProfileApprovalPolicy.generateProfileTopic()
            current[profileId] = result!!
            prefs[topicsKey] = encode(current)
        }
        result!!
    }

    suspend fun disable(profileId: Int) = mutex.withLock {
        update { current ->
            current.remove(profileId)
            current
        }
    }

    suspend fun removeProfile(profileId: Int) = disable(profileId)

    suspend fun clearAll() = mutex.withLock {
        dataStore.edit { prefs -> prefs.clear() }
    }

    private suspend fun update(transform: (MutableMap<Int, String>) -> Map<Int, String>) {
        dataStore.edit { prefs ->
            prefs[topicsKey] = encode(transform(decode(prefs[topicsKey]).toMutableMap()))
        }
    }

    private fun encode(map: Map<Int, String>): String =
        map.entries
            .filter { (id, topic) -> id > 0 && NtfyProfileApprovalPolicy.isProfileTopic(topic) }
            .joinToString(separator = ",") { (id, topic) -> "$id=$topic" }

    private fun decode(raw: String?): Map<Int, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0 || separator == entry.lastIndex) return@mapNotNull null
            val id = entry.substring(0, separator).toIntOrNull() ?: return@mapNotNull null
            val topic = entry.substring(separator + 1)
            if (id <= 0 || !NtfyProfileApprovalPolicy.isProfileTopic(topic)) {
                return@mapNotNull null
            }
            id to topic
        }.toMap()
    }
}
