package com.nuvio.tv.core.profileapproval

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom

enum class NtfyApprovalDecision {
    YES,
    NO
}

/**
 * Wire-level rules for the direct ntfy profile-unlock transport.
 *
 * Topics and response tokens are bearer capabilities. They are deliberately
 * generated with no profile or device information in them.
 */
object NtfyProfileApprovalPolicy {
    const val NTFY_BASE_URL = "https://ntfy.sh"
    const val REQUEST_TIMEOUT_MS = 2 * 60 * 1_000L
    const val RETRY_COOLDOWN_MS = 5_000L
    const val RESPONSE_PAYLOAD_PREFIX = "nuvio-profile-unlock-v1"

    private const val PROFILE_TOPIC_PREFIX = "nuvio-"
    private const val RESPONSE_TOPIC_PREFIX = "nuvio-r-"
    private const val TOPIC_RANDOM_BYTES = 24
    private const val TOKEN_RANDOM_BYTES = 32
    private val secureRandom = SecureRandom()
    private val hexDigits = "0123456789abcdef"

    fun generateProfileTopic(): String =
        PROFILE_TOPIC_PREFIX + randomHex(TOPIC_RANDOM_BYTES)

    fun generateResponseTopic(): String =
        RESPONSE_TOPIC_PREFIX + randomHex(TOPIC_RANDOM_BYTES)

    fun generateResponseToken(): String = randomHex(TOKEN_RANDOM_BYTES)

    fun isProfileTopic(topic: String): Boolean =
        topic.matches(Regex("""nuvio-[a-f0-9]{48}"""))

    fun topicWebUrl(topic: String): String {
        require(isProfileTopic(topic)) { "Invalid ntfy profile topic" }
        return "$NTFY_BASE_URL/$topic"
    }

    fun responseTopicUrl(topic: String): String {
        require(topic.matches(Regex("""nuvio-r-[a-f0-9]{48}"""))) {
            "Invalid ntfy response topic"
        }
        return "$NTFY_BASE_URL/$topic"
    }

    /**
     * The QR uses the documented HTTPS topic URL. It works with a browser/PWA
     * and can be handed to the native ntfy app when a phone scanner does not
     * open the app directly.
     */
    fun subscriptionQrContent(topic: String): String = topicWebUrl(topic)

    fun responsePayload(token: String, decision: NtfyApprovalDecision): String =
        "$RESPONSE_PAYLOAD_PREFIX|$token|${decision.wireValue}"

    fun parseResponsePayload(message: String, expectedToken: String): NtfyApprovalDecision? {
        val parts = message.split('|')
        if (parts.size != 3 ||
            parts[0] != RESPONSE_PAYLOAD_PREFIX ||
            parts[1] != expectedToken
        ) {
            return null
        }
        return when (parts[2]) {
            "yes" -> NtfyApprovalDecision.YES
            "no" -> NtfyApprovalDecision.NO
            else -> null
        }
    }

    /**
     * Uses ntfy's JSON action format with HTTP POST actions. No Safari/browser
     * GET approval is emitted; native iOS behavior remains unverified.
     */
    fun buildNotificationJson(
        profileName: String,
        profileTopic: String,
        responseTopic: String,
        responseToken: String,
        deviceName: String = "Android TV"
    ): String {
        require(isProfileTopic(profileTopic)) { "Invalid ntfy profile topic" }
        val responseUrl = responseTopicUrl(responseTopic)
        val safeProfileName = sanitizeDisplayName(profileName, "profile", 80)
        val safeDeviceName = sanitizeDisplayName(deviceName, "Android TV", 64)

        return buildJsonObject {
            put("topic", profileTopic)
            put("title", "Nuvio TV unlock request")
            put(
                "message",
                "Allow Nuvio TV on \"$safeDeviceName\" to open the \"$safeProfileName\" profile?"
            )
            put("priority", 5)
            put("actions", buildJsonArray {
                add(buildJsonObject {
                    put("action", "http")
                    put("label", "Yes")
                    put("url", responseUrl)
                    put("method", "POST")
                    put("body", responsePayload(responseToken, NtfyApprovalDecision.YES))
                    put("clear", true)
                })
                add(buildJsonObject {
                    put("action", "http")
                    put("label", "No")
                    put("url", responseUrl)
                    put("method", "POST")
                    put("body", responsePayload(responseToken, NtfyApprovalDecision.NO))
                    put("clear", true)
                })
            })
        }.toString()
    }

    private fun sanitizeDisplayName(value: String, fallback: String, maxLength: Int): String =
        value
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString("")
            .trim()
            .take(maxLength)
            .ifBlank { fallback }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return buildString(byteCount * 2) {
            bytes.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(hexDigits[unsigned ushr 4])
                append(hexDigits[unsigned and 0x0f])
            }
        }
    }

    private val NtfyApprovalDecision.wireValue: String
        get() = if (this == NtfyApprovalDecision.YES) "yes" else "no"
}

/**
 * Keeps repeated phone approval requests from becoming a notification spam
 * loop. The cooldown is intentionally applied even after cancellation.
 */
internal class NtfyApprovalRetryGate(
    private val cooldownMs: Long = NtfyProfileApprovalPolicy.RETRY_COOLDOWN_MS
) {
    private val lastStartedAtMs = mutableMapOf<Int, Long>()

    @Synchronized
    fun checkAndRecord(profileId: Int, nowMs: Long): Long {
        val lastStarted = lastStartedAtMs[profileId]
        if (lastStarted != null) {
            val remaining = lastStarted + cooldownMs - nowMs
            if (remaining > 0L) return remaining
        }
        lastStartedAtMs[profileId] = nowMs
        return 0L
    }
}
