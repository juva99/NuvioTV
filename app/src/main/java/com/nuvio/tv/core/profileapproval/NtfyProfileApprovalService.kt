package com.nuvio.tv.core.profileapproval

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.auth.currentDeviceClientMetadata
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

sealed interface NtfyApprovalOutcome {
    data object Approved : NtfyApprovalOutcome
    data object Denied : NtfyApprovalOutcome
    data object TimedOut : NtfyApprovalOutcome
    data object NotConfigured : NtfyApprovalOutcome
    data object Busy : NtfyApprovalOutcome
    data object Failed : NtfyApprovalOutcome
    data class RateLimited(val retryAfterSeconds: Int) : NtfyApprovalOutcome
}

@Singleton
class NtfyProfileApprovalService @Inject constructor(
    @Named("ntfy") private val client: OkHttpClient,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val authManager: AuthManager,
    @ApplicationContext private val context: Context
) {
    private val requestLock = Any()
    private var activeRequest: ActiveRequest? = null
    private val retryGate = NtfyApprovalRetryGate()

    suspend fun requestUnlock(profileId: Int, profileName: String): NtfyApprovalOutcome {
        val accountId = authManager.currentUserId ?: return NtfyApprovalOutcome.Failed
        val job = currentCoroutineContext()[Job]
        val request = synchronized(requestLock) {
            if (activeRequest != null) return NtfyApprovalOutcome.Busy
            val retryAfterMs = retryGate.checkAndRecord(profileId, android.os.SystemClock.elapsedRealtime())
            if (retryAfterMs > 0L) {
                return NtfyApprovalOutcome.RateLimited(((retryAfterMs + 999L) / 1_000L).toInt())
            }
            ActiveRequest(job).also { activeRequest = it }
        }
        return try {
            withTimeout(NtfyProfileApprovalPolicy.REQUEST_TIMEOUT_MS) {
                val profileTopic = profileSettingsSyncService.revalidatePhoneApprovalPairing(profileId)
                    .getOrThrow() ?: return@withTimeout NtfyApprovalOutcome.NotConfigured
                if (authManager.currentUserId != accountId) return@withTimeout NtfyApprovalOutcome.Failed
                val outcome = executeRequest(request, profileName, profileTopic)
                if (outcome != NtfyApprovalOutcome.Approved) return@withTimeout outcome
                if (authManager.currentUserId != accountId) return@withTimeout NtfyApprovalOutcome.Failed
                val currentTopic = profileSettingsSyncService.revalidatePhoneApprovalPairing(profileId)
                    .getOrThrow()
                if (authManager.currentUserId == accountId && currentTopic == profileTopic) {
                    NtfyApprovalOutcome.Approved
                } else {
                    NtfyApprovalOutcome.Failed
                }
            }
        } catch (_: TimeoutCancellationException) {
            NtfyApprovalOutcome.TimedOut
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Exception messages/URLs can contain bearer capabilities.
            Log.w("NtfyProfileApproval", "Phone approval failed (${error.javaClass.simpleName})")
            NtfyApprovalOutcome.Failed
        } finally {
            request.cancelCalls()
            synchronized(requestLock) {
                if (activeRequest === request) activeRequest = null
            }
        }
    }

    fun cancelActiveRequest() {
        synchronized(requestLock) {
            activeRequest?.job?.cancel()
            activeRequest?.cancelCalls()
        }
    }

    private suspend fun executeRequest(
        request: ActiveRequest,
        profileName: String,
        profileTopic: String
    ): NtfyApprovalOutcome {
        val notification = NtfyProfileApprovalPolicy.buildNotificationJson(
            profileName, profileTopic, request.responseTopic, request.responseToken,
            currentDeviceClientMetadata(context).deviceName
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder()
                .url("${NtfyProfileApprovalPolicy.responseTopicUrl(request.responseTopic)}/json")
                .build())
            request.responseCall = call
            continuation.invokeOnCancellation { request.cancelCalls() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            check(response.isSuccessful) { "ntfy subscription rejected" }
                            val source = response.body?.source() ?: error("Missing ntfy response body")
                            val publish = client.newCall(Request.Builder()
                                .url(NtfyProfileApprovalPolicy.NTFY_BASE_URL)
                                .post(notification)
                                .build())
                            request.publishCall = publish
                            if (!continuation.isActive) {
                                publish.cancel()
                                return
                            }
                            publish.execute().use { check(it.isSuccessful) { "ntfy publish rejected" } }
                            while (continuation.isActive) {
                                val line = source.readUtf8Line() ?: error("ntfy stream closed")
                                val decision = runCatching {
                                    val message = Json.parseToJsonElement(line).jsonObject
                                    if (message["event"]?.jsonPrimitive?.content != "message" ||
                                        message["topic"]?.jsonPrimitive?.content != request.responseTopic
                                    ) return@runCatching null
                                    NtfyProfileApprovalPolicy.parseResponsePayload(
                                        message["message"]?.jsonPrimitive?.content.orEmpty(),
                                        request.responseToken
                                    )
                                }.getOrNull() ?: continue
                                continuation.resume(
                                    if (decision == NtfyApprovalDecision.YES) NtfyApprovalOutcome.Approved
                                    else NtfyApprovalOutcome.Denied
                                )
                                return
                            }
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private class ActiveRequest(val job: Job?) {
        val responseTopic = NtfyProfileApprovalPolicy.generateResponseTopic()
        val responseToken = NtfyProfileApprovalPolicy.generateResponseToken()
        @Volatile var responseCall: Call? = null
        @Volatile var publishCall: Call? = null

        fun cancelCalls() {
            responseCall?.cancel()
            publishCall?.cancel()
        }
    }
}
