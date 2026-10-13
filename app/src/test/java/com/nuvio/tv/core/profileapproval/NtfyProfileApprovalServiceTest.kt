package com.nuvio.tv.core.profileapproval

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sync.ProfileSettingsSyncService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class NtfyProfileApprovalServiceTest {
    private val profileTopic = "nuvio-" + "a".repeat(48)
    private val responseTopic = "nuvio-r-" + "b".repeat(48)
    private val token = "c".repeat(64)
    private val settings = mockk<ProfileSettingsSyncService>()
    private val auth = mockk<AuthManager>()
    private val streamCancelled = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var service: NtfyProfileApprovalService

    @Before
    fun setUp() {
        mockkObject(NtfyProfileApprovalPolicy)
        every { NtfyProfileApprovalPolicy.generateResponseTopic() } returns responseTopic
        every { NtfyProfileApprovalPolicy.generateResponseToken() } returns token
        every { auth.currentUserId } returns "account"
        coEvery { settings.revalidatePhoneApprovalPairing(any()) } returns Result.success(profileTopic)
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    if (call.request().url.encodedPath.endsWith("/json")) streamCancelled.countDown()
                }
            })
            .build()
        service = NtfyProfileApprovalService(client, settings, auth, mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        service.cancelActiveRequest()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
        unmockkObject(NtfyProfileApprovalPolicy)
    }

    @Test
    fun `two minute timeout cancels a stream blocked waiting for a response`() = runTest {
        enqueueStalledStream()
        val result = async { service.requestUnlock(1, "Primary") }
        runCurrent()
        awaitSubscriptionAndPublish()

        advanceTimeBy(NtfyProfileApprovalPolicy.REQUEST_TIMEOUT_MS)
        runCurrent()

        assertTrue(result.isCompleted)
        assertEquals(NtfyApprovalOutcome.TimedOut, result.await())
        assertTrue(streamCancelled.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `explicit cancellation closes the stream and releases the active request`() = runTest {
        enqueueStalledStream()
        val result = async { service.requestUnlock(1, "Primary") }
        runCurrent()
        awaitSubscriptionAndPublish()

        service.cancelActiveRequest()
        runCurrent()

        assertTrue(result.isCancelled)
        assertTrue(streamCancelled.await(5, TimeUnit.SECONDS))
        coEvery { settings.revalidatePhoneApprovalPairing(1) } returns Result.success(null)
        assertEquals(NtfyApprovalOutcome.NotConfigured, service.requestUnlock(1, "Primary"))
    }

    @Test
    fun `approval is accepted only while the remote pairing is unchanged`() = runBlocking {
        coEvery { settings.revalidatePhoneApprovalPairing(1) } returnsMany listOf(
            Result.success(profileTopic), Result.success(null)
        )
        assertEquals(NtfyApprovalOutcome.Failed, respondWith("yes"))
    }

    @Test
    fun `consecutive unlock attempts each publish without a cooldown`() = runBlocking {
        repeat(2) {
            assertEquals(NtfyApprovalOutcome.Approved, respondWith("yes"))
        }
    }

    @Test
    fun `no denies the unlock`() = runBlocking {
        assertEquals(NtfyApprovalOutcome.Denied, respondWith("no"))
    }

    private suspend fun respondWith(decision: String): NtfyApprovalOutcome {
        server.enqueue(MockResponse().setBody(
            """{"event":"message","topic":"$responseTopic","message":"nuvio-profile-unlock-v1|$token|$decision"}""" + "\n"
        ))
        server.enqueue(MockResponse().setBody("{}"))
        val result = withTimeout(5_000) { service.requestUnlock(1, "Primary") }
        awaitSubscriptionAndPublish()
        return result
    }

    private fun enqueueStalledStream() {
        server.enqueue(MockResponse().setBody("waiting\n").setBodyDelay(1, TimeUnit.DAYS))
        server.enqueue(MockResponse().setBody("{}"))
    }

    private fun awaitSubscriptionAndPublish() {
        assertEquals("/$responseTopic/json", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        assertEquals("POST", server.takeRequest(5, TimeUnit.SECONDS)?.method)
    }
}
