package com.nuvio.tv.core.profileapproval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyProfileApprovalPolicyTest {
    private val json = Json

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
        val notification = json.parseToJsonElement(
            NtfyProfileApprovalPolicy.buildNotificationJson(
                profileName = "Living room",
                profileTopic = profileTopic,
                responseTopic = responseTopic,
                responseToken = token
            )
        ).jsonObject
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
    fun `retry gate applies cooldown after cancellation or timeout`() {
        val gate = NtfyApprovalRetryGate(cooldownMs = 5_000L)

        assertEquals(0L, gate.checkAndRecord(profileId = 2, nowMs = 100L))
        assertEquals(4_000L, gate.checkAndRecord(profileId = 2, nowMs = 1_000L))
        assertEquals(0L, gate.checkAndRecord(profileId = 2, nowMs = 5_100L))
    }
}
