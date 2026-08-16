package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.GitHubIssueReportingDataStore
import com.nuvio.tv.data.remote.api.GitHubOAuthApi
import com.nuvio.tv.data.remote.dto.GitHubOAuthTokenResponseDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubDeviceAuthorizationSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresAtMs: Long,
    val intervalSeconds: Long
)

sealed interface GitHubDevicePollResult {
    data object Pending : GitHubDevicePollResult

    data class SlowDown(
        val intervalSeconds: Long
    ) : GitHubDevicePollResult

    data class Authorized(
        val token: GitHubOAuthTokenResponseDto
    ) : GitHubDevicePollResult

    data class Failed(
        val code: String,
        val message: String
    ) : GitHubDevicePollResult
}

@Singleton
class GitHubIssueAuthorizationRepository @Inject constructor(
    private val githubOAuthApi: GitHubOAuthApi,
    private val settingsStore: GitHubIssueReportingDataStore
) {
    fun isConfigured(): Boolean = clientId.isNotBlank()

    suspend fun requestDeviceAuthorization(): Result<GitHubDeviceAuthorizationSession> {
        if (clientId.isBlank()) {
            return Result.failure(
                IllegalStateException("GitHub App client ID is not configured")
            )
        }

        return try {
            val response = githubOAuthApi.requestDeviceCode(clientId)
            if (!response.isSuccessful) {
                return Result.failure(
                    IllegalStateException("GitHub device authorization failed: HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: return Result.failure(
                    IllegalStateException("GitHub device authorization returned an empty response")
                )
            val deviceCode = body.deviceCode?.trim()?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("GitHub device authorization returned no device code")
                )
            val userCode = body.userCode?.trim()?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("GitHub device authorization returned no user code")
                )
            val verificationUri = body.verificationUri?.trim()?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException("GitHub device authorization returned no verification URL")
                )
            val expiresInSeconds = body.expiresInSeconds
                ?.takeIf { it > 0L }
                ?: DEFAULT_DEVICE_CODE_EXPIRY_SECONDS
            val intervalSeconds = body.interval
                ?.takeIf { it > 0L }
                ?: DEFAULT_POLL_INTERVAL_SECONDS

            Result.success(
                GitHubDeviceAuthorizationSession(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUri = verificationUri,
                    expiresAtMs = System.currentTimeMillis() + expiresInSeconds * 1_000L,
                    intervalSeconds = intervalSeconds
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollDeviceAuthorization(
        session: GitHubDeviceAuthorizationSession
    ): GitHubDevicePollResult {
        if (clientId.isBlank()) {
            return GitHubDevicePollResult.Failed(
                code = "missing_client_id",
                message = "GitHub App client ID is not configured"
            )
        }
        return try {
            val response = githubOAuthApi.pollDeviceToken(
                clientId = clientId,
                deviceCode = session.deviceCode,
                grantType = DEVICE_GRANT_TYPE,
                repositoryId = BuildConfig.GITHUB_ISSUE_REPOSITORY_ID
                    .trim()
                    .takeIf { it.isNotBlank() }
            )
            if (!response.isSuccessful) {
                return GitHubDevicePollResult.Failed(
                    code = "http_${response.code()}",
                    message = "GitHub device authorization failed: HTTP ${response.code()}"
                )
            }

            val body = response.body()
                ?: return GitHubDevicePollResult.Failed(
                    code = "empty_response",
                    message = "GitHub device authorization returned an empty response"
                )
            val accessToken = body.accessToken?.trim()?.takeIf { it.isNotBlank() }
            if (accessToken != null) {
                return GitHubDevicePollResult.Authorized(body)
            }

            when (body.error?.trim()?.lowercase()) {
                "authorization_pending" -> GitHubDevicePollResult.Pending
                "slow_down" -> GitHubDevicePollResult.SlowDown(
                    intervalSeconds = body.interval
                        ?.takeIf { it > 0L }
                        ?: session.intervalSeconds + SLOW_DOWN_INCREMENT_SECONDS
                )
                "expired_token" -> GitHubDevicePollResult.Failed(
                    code = "expired_token",
                    message = "The GitHub authorization code expired"
                )
                "access_denied" -> GitHubDevicePollResult.Failed(
                    code = "access_denied",
                    message = "GitHub authorization was cancelled"
                )
                else -> GitHubDevicePollResult.Failed(
                    code = body.error?.trim().takeIf { !it.isNullOrBlank() } ?: "unknown_error",
                    message = body.errorDescription?.trim().takeIf { !it.isNullOrBlank() }
                        ?: "GitHub authorization could not be completed"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            GitHubDevicePollResult.Failed(
                code = "network_error",
                message = e.message?.trim().takeIf { !it.isNullOrBlank() }
                    ?: "GitHub authorization could not be completed"
            )
        }
    }

    suspend fun saveAuthorizedToken(token: GitHubOAuthTokenResponseDto) {
        val accessToken = token.accessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: error("GitHub OAuth response has no access token")
        val nowMs = System.currentTimeMillis()
        settingsStore.setOAuthTokens(
            accessToken = accessToken,
            refreshToken = token.refreshToken,
            accessTokenExpiresAtMs = token.expiresInSeconds.toExpiryMs(nowMs),
            refreshTokenExpiresAtMs = token.refreshTokenExpiresInSeconds.toExpiryMs(nowMs)
        )
    }

    suspend fun accessToken(): Result<String> {
        val tokenData = settingsStore.readTokenData()
            ?: return Result.failure(
                IllegalStateException("A GitHub issue token is not configured")
            )
        val accessToken = tokenData.accessToken.trim().takeIf { it.isNotBlank() }
            ?: return Result.failure(
                IllegalStateException("The stored GitHub issue token is empty")
            )
        val accessExpiresAtMs = tokenData.accessTokenExpiresAtMs
        if (accessExpiresAtMs == null ||
            System.currentTimeMillis() < accessExpiresAtMs - TOKEN_REFRESH_SKEW_MS
        ) {
            return Result.success(accessToken)
        }

        val refreshToken = tokenData.refreshToken?.trim()?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                IllegalStateException("GitHub authorization expired; reconnect GitHub")
            )
        if (tokenData.refreshTokenExpiresAtMs?.let {
                System.currentTimeMillis() >= it - TOKEN_REFRESH_SKEW_MS
            } == true
        ) {
            return Result.failure(
                IllegalStateException("GitHub authorization expired; reconnect GitHub")
            )
        }

        if (clientId.isBlank()) {
            return Result.failure(
                IllegalStateException("GitHub App client ID is not configured")
            )
        }

        return try {
            val response = githubOAuthApi.refreshDeviceToken(
                clientId = clientId,
                grantType = REFRESH_GRANT_TYPE,
                refreshToken = refreshToken
            )
            if (!response.isSuccessful) {
                return Result.failure(
                    IllegalStateException("GitHub token refresh failed: HTTP ${response.code()}")
                )
            }

            val body = response.body()
                ?: return Result.failure(
                    IllegalStateException("GitHub token refresh returned an empty response")
                )
            val refreshedAccessToken = body.accessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: return Result.failure(
                    IllegalStateException(
                        body.errorDescription?.trim()?.takeIf { it.isNotBlank() }
                            ?: "GitHub token refresh failed"
                    )
                )
            val nowMs = System.currentTimeMillis()
            settingsStore.setOAuthTokens(
                accessToken = refreshedAccessToken,
                refreshToken = body.refreshToken ?: refreshToken,
                accessTokenExpiresAtMs = body.expiresInSeconds.toExpiryMs(nowMs)
                    ?: tokenData.accessTokenExpiresAtMs,
                refreshTokenExpiresAtMs = body.refreshTokenExpiresInSeconds.toExpiryMs(nowMs)
                    ?: tokenData.refreshTokenExpiresAtMs
            )
            Result.success(refreshedAccessToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val clientId: String
        get() = BuildConfig.GITHUB_ISSUE_CLIENT_ID.trim()

    private fun Long?.toExpiryMs(nowMs: Long): Long? =
        this?.takeIf { it > 0L }?.let { seconds ->
            nowMs + seconds * 1_000L
        }

    private companion object {
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val REFRESH_GRANT_TYPE = "refresh_token"
        const val DEFAULT_DEVICE_CODE_EXPIRY_SECONDS = 900L
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5L
        const val SLOW_DOWN_INCREMENT_SECONDS = 5L
        const val TOKEN_REFRESH_SKEW_MS = 60_000L
    }
}
