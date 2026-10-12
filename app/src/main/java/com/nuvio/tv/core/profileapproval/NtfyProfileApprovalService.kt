package com.nuvio.tv.core.profileapproval

import com.nuvio.tv.data.local.ProfileApprovalPairingDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
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
import okio.BufferedSource
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
    private val pairingDataStore: ProfileApprovalPairingDataStore
) {
    private val activeRequestLock = Any()
    private var activeRequest: ActiveRequest? = null
    private val retryGate = NtfyApprovalRetryGate()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun requestUnlock(profileId: Int, profileName: String): NtfyApprovalOutcome {
        val profileTopic = pairingDataStore.topicFor(profileId)
            ?: return NtfyApprovalOutcome.NotConfigured
        val request = synchronized(activeRequestLock) {
            if (activeRequest != null) return NtfyApprovalOutcome.Busy
            val retryAfterMs = retryGate.checkAndRecord(
                profileId = profileId,
                nowMs = android.os.SystemClock.elapsedRealtime()
            )
            if (retryAfterMs > 0L) {
                return NtfyApprovalOutcome.RateLimited(
                    retryAfterSeconds = ((retryAfterMs + 999L) / 1_000L).toInt()
                )
            }
            ActiveRequest(
                profileId = profileId,
                profileTopic = profileTopic,
                responseTopic = NtfyProfileApprovalPolicy.generateResponseTopic(),
                responseToken = NtfyProfileApprovalPolicy.generateResponseToken()
            ).also { activeRequest = it }
        }

        return try {
            val outcome = withTimeout(NtfyProfileApprovalPolicy.REQUEST_TIMEOUT_MS) {
                executeRequest(request, profileName)
            }
            if (outcome == NtfyApprovalOutcome.Approved &&
                pairingDataStore.topicFor(request.profileId) != request.profileTopic
            ) {
                NtfyApprovalOutcome.Failed
            } else {
                outcome
            }
        } catch (_: TimeoutCancellationException) {
            NtfyApprovalOutcome.TimedOut
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Never log exceptions here: OkHttp exceptions can contain the
            // response topic, which is a live bearer capability.
            NtfyApprovalOutcome.Failed
        } finally {
            request.responseCall?.cancel()
            request.publishCall?.cancel()
            synchronized(activeRequestLock) {
                if (activeRequest === request) {
                    activeRequest = null
                }
            }
        }
    }

    fun cancelActiveRequest() {
        synchronized(activeRequestLock) {
            activeRequest?.responseCall?.cancel()
            activeRequest?.publishCall?.cancel()
        }
    }

    private suspend fun executeRequest(
        request: ActiveRequest,
        profileName: String
    ): NtfyApprovalOutcome {
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            request.responseCall?.cancel()
            request.publishCall?.cancel()
        }
        val subscriptionRequest = Request.Builder()
            .url("${NtfyProfileApprovalPolicy.responseTopicUrl(request.responseTopic)}/json")
            .header("Accept", "application/x-ndjson")
            .build()
        try {
            val (call, response) = openSubscription(request, subscriptionRequest)
            try {
                currentCoroutineContext().ensureActive()
                publishUnlockRequest(request, profileName)
                val source = response.body?.source() ?: throw IOException("Missing ntfy response body")
                return withContext(Dispatchers.IO) {
                    readFirstMatchingResponse(source, request)
                }
            } finally {
                response.close()
                call.cancel()
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private suspend fun openSubscription(
        request: ActiveRequest,
        subscriptionRequest: Request
    ): Pair<Call, Response> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(subscriptionRequest)
        request.responseCall = call
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(IOException("ntfy subscription failed"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful || response.body == null) {
                    response.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("ntfy subscription rejected"))
                    }
                } else if (continuation.isActive) {
                    continuation.resume(call to response)
                } else {
                    response.close()
                    call.cancel()
                }
            }
        })
    }

    private suspend fun publishUnlockRequest(
        request: ActiveRequest,
        profileName: String
    ) = withContext(Dispatchers.IO) {
        val body = NtfyProfileApprovalPolicy.buildNotificationJson(
            profileName = profileName,
            profileTopic = request.profileTopic,
            responseTopic = request.responseTopic,
            responseToken = request.responseToken
        ).toRequestBody(JSON_MEDIA_TYPE)
        val publishRequest = Request.Builder()
            .url(NtfyProfileApprovalPolicy.NTFY_BASE_URL)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val call = client.newCall(publishRequest)
        request.publishCall = call
        call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("ntfy publish rejected")
            }
        }
        request.publishCall = null
    }

    private suspend fun readFirstMatchingResponse(
        source: BufferedSource,
        request: ActiveRequest
    ): NtfyApprovalOutcome {
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = source.readUtf8Line() ?: throw IOException("ntfy stream closed")
            if (line.isBlank()) continue
            val parsed = runCatching {
                val message = json.parseToJsonElement(line).jsonObject
                if (message["event"]?.jsonPrimitive?.content != "message") return@runCatching null
                if (message["topic"]?.jsonPrimitive?.content != request.responseTopic) {
                    return@runCatching null
                }
                val messageId = message["id"]?.jsonPrimitive?.content ?: return@runCatching null
                if (!request.seenMessageIds.add(messageId)) return@runCatching null
                val body = message["message"]?.jsonPrimitive?.content ?: return@runCatching null
                NtfyProfileApprovalPolicy.parseResponsePayload(body, request.responseToken)
            }.getOrNull() ?: continue

            return when (parsed) {
                NtfyApprovalDecision.YES -> NtfyApprovalOutcome.Approved
                NtfyApprovalDecision.NO -> NtfyApprovalOutcome.Denied
            }
        }
    }

    private class ActiveRequest(
        val profileId: Int,
        val profileTopic: String,
        val responseTopic: String,
        val responseToken: String
    ) {
        @Volatile
        var responseCall: Call? = null
        @Volatile
        var publishCall: Call? = null
        val seenMessageIds = mutableSetOf<String>()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
