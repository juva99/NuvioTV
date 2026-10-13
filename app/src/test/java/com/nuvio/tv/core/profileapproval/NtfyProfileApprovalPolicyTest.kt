package com.nuvio.tv.core.profileapproval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyProfileApprovalPolicyTest {
    @Test
    fun `profile and response capabilities are random and structurally isolated`() {
        val profileTopic = NtfyProfileApprovalPolicy.generateProfileTopic()
        val responseTopic = NtfyProfileApprovalPolicy.generateResponseTopic()
        val token = NtfyProfileApprovalPolicy.generateResponseToken()

        assertTrue(NtfyProfileApprovalPolicy.isProfileTopic(profileTopic))
        assertNotEquals(profileTopic, NtfyProfileApprovalPolicy.generateProfileTopic())
        assertNotEquals(responseTopic, NtfyProfileApprovalPolicy.generateResponseTopic())
        assertEquals(64, token.length)
        assertTrue(token.all { it in "0123456789abcdef" })
        assertTrue(responseTopic.startsWith("nuvio-r-"))
    }

    @Test
    fun `only the exact current response token and decision are accepted`() {
        val token = NtfyProfileApprovalPolicy.generateResponseToken()
        val yes = NtfyProfileApprovalPolicy.responsePayload(token, NtfyApprovalDecision.YES)

        assertEquals(
            NtfyApprovalDecision.YES,
            NtfyProfileApprovalPolicy.parseResponsePayload(yes, token)
        )
        assertEquals(
            NtfyApprovalDecision.NO,
            NtfyProfileApprovalPolicy.parseResponsePayload(
                NtfyProfileApprovalPolicy.responsePayload(token, NtfyApprovalDecision.NO),
                token
            )
        )
        assertNull(NtfyProfileApprovalPolicy.parseResponsePayload(yes, "different-token"))
        assertNull(NtfyProfileApprovalPolicy.parseResponsePayload("$yes|extra", token))
        assertNull(
            NtfyProfileApprovalPolicy.parseResponsePayload(
                "${
                    NtfyProfileApprovalPolicy.RESPONSE_PAYLOAD_PREFIX
                }|$token|maybe",
                token
            )
        )
    }

    @Test
    fun `notification actions post yes and no to the fresh response topic`() {
        val profileTopic = NtfyProfileApprovalPolicy.generateProfileTopic()
        val responseTopic = NtfyProfileApprovalPolicy.generateResponseTopic()
        val token = NtfyProfileApprovalPolicy.generateResponseToken()
        val notification = notification(profileTopic, responseTopic, token)
        val actions = notification.getValue("actions").jsonArray

        assertEquals(profileTopic, notification.getValue("topic").jsonPrimitive.content)
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.jsonObject.getValue("action").jsonPrimitive.content == "http" })
        assertTrue(actions.all { it.jsonObject.getValue("method").jsonPrimitive.content == "POST" })
        assertTrue(actions.all {
            it.jsonObject.getValue("url").jsonPrimitive.content ==
                NtfyProfileApprovalPolicy.responseTopicUrl(responseTopic)
        })
        assertEquals(
            NtfyProfileApprovalPolicy.responsePayload(token, NtfyApprovalDecision.YES),
            actions[0].jsonObject.getValue("body").jsonPrimitive.content
        )
        assertEquals(
            NtfyProfileApprovalPolicy.responsePayload(token, NtfyApprovalDecision.NO),
            actions[1].jsonObject.getValue("body").jsonPrimitive.content
        )
    }

    @Test
    fun `concurrent TVs get distinct response capabilities and visible device names`() {
        val profileTopic = NtfyProfileApprovalPolicy.generateProfileTopic()
        val firstResponseTopic = NtfyProfileApprovalPolicy.generateResponseTopic()
        val secondResponseTopic = NtfyProfileApprovalPolicy.generateResponseTopic()
        val firstToken = NtfyProfileApprovalPolicy.generateResponseToken()
        val secondToken = NtfyProfileApprovalPolicy.generateResponseToken()

        val first = notification(profileTopic, firstResponseTopic, firstToken, deviceName = "TV downstairs")
        val second = notification(profileTopic, secondResponseTopic, secondToken, deviceName = "TV upstairs")

        assertNotEquals(
            first.getValue("actions").jsonArray[0].jsonObject.getValue("url"),
            second.getValue("actions").jsonArray[0].jsonObject.getValue("url")
        )
        assertTrue(
            first.getValue("message").jsonPrimitive.content.contains("TV downstairs")
        )
        assertTrue(
            second.getValue("message").jsonPrimitive.content.contains("TV upstairs")
        )
        assertNull(
            NtfyProfileApprovalPolicy.parseResponsePayload(
                NtfyProfileApprovalPolicy.responsePayload(
                    firstToken,
                    NtfyApprovalDecision.YES
                ),
                secondToken
            )
        )
    }

    @Test
    fun `notification labels are bounded and control characters are sanitized`() {
        val notification = notification(
            profileName = "profile\n".repeat(100),
            deviceName = "device\r\n\t".repeat(100)
        )

        val message = notification.getValue("message").jsonPrimitive.content
        assertTrue(message.length <= 200)
        assertTrue(message.none(Char::isISOControl))
    }

    @Test
    fun `pairing QR opens the native subscription on the notification server`() {
        val topic = NtfyProfileApprovalPolicy.generateProfileTopic()
        assertEquals(
            "ntfy://ntfy.sh/$topic",
            NtfyProfileApprovalPolicy.topicSubscriptionUrl(topic)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `subscription links reject topics containing URL parameters`() {
        NtfyProfileApprovalPolicy.topicSubscriptionUrl(
            NtfyProfileApprovalPolicy.generateProfileTopic() + "?secure=false"
        )
    }

    private fun notification(
        profileTopic: String = NtfyProfileApprovalPolicy.generateProfileTopic(),
        responseTopic: String = NtfyProfileApprovalPolicy.generateResponseTopic(),
        token: String = NtfyProfileApprovalPolicy.generateResponseToken(),
        profileName: String = "Living room",
        deviceName: String = "Android TV"
    ): JsonObject = Json.parseToJsonElement(
        NtfyProfileApprovalPolicy.buildNotificationJson(profileName, profileTopic, responseTopic, token, deviceName)
    ).jsonObject
}
