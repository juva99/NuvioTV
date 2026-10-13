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

enum class PhoneApprovalPairingAction {
    Enable,
    Regenerate,
    Disable
}

object NtfyProfileApprovalPolicy {
    const val NTFY_BASE_URL = "https://ntfy.sh"
    const val REQUEST_TIMEOUT_MS = 2 * 60 * 1_000L
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

    fun topicSubscriptionUrl(topic: String): String {
        require(isProfileTopic(topic)) { "Invalid ntfy profile topic" }
        return "ntfy://${NTFY_BASE_URL.removePrefix("https://")}/$topic"
    }

    fun responseTopicUrl(topic: String): String {
        require(topic.matches(Regex("""nuvio-r-[a-f0-9]{48}"""))) {
            "Invalid ntfy response topic"
        }
        return "$NTFY_BASE_URL/$topic"
    }

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
                NtfyApprovalDecision.entries.forEach { decision ->
                    add(buildJsonObject {
                        put("action", "http")
                        put("label", if (decision == NtfyApprovalDecision.YES) "Yes" else "No")
                        put("url", responseUrl)
                        put("method", "POST")
                        put("body", responsePayload(responseToken, decision))
                        put("clear", true)
                    })
                }
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
