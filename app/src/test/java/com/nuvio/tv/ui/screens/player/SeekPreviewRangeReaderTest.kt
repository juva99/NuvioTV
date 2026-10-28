package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class SeekPreviewRangeReaderTest {
    private val client = OkHttpClient()

    private fun response(body: String, start: Long = 0, total: Long = body.length.toLong()) =
        MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes $start-${start + body.length - 1}/$total")
            .setBody(body)

    @Test
    fun `range reads forward auth headers and reuse a small window`() {
        MockWebServer().use { server ->
            server.enqueue(response("abcdefgh"))
            server.start()
            SeekPreviewRangeReader(client, server.url("/video").toString(), mapOf(
                "Authorization" to "Bearer test", "Referer" to "https://example.test/",
                "Range" to "bytes=900-", "Accept-Encoding" to "gzip"
            )).use { reader ->
                assertEquals(8, reader.size())
                val data = ByteArray(3)
                assertEquals(3, reader.readAt(2, data, 0, 3))
                assertArrayEquals("cde".toByteArray(), data)
                assertEquals(-1, reader.readAt(8, data, 0, 3))
                assertEquals(1, server.requestCount)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("bytes=0-131071", request.getHeader("Range"))
                assertEquals("identity", request.getHeader("Accept-Encoding"))
                assertEquals("Bearer test", request.getHeader("Authorization"))
                assertEquals("https://example.test/", request.getHeader("Referer"))
            }
        }
    }

    @Test
    fun `redirect bodies are cancelled rather than drained outside the byte budget`() {
        val transferred = AtomicLong()
        val measuredClient = client.newBuilder().eventListener(object : EventListener() {
            override fun responseBodyEnd(call: Call, byteCount: Long) {
                transferred.addAndGet(byteCount)
            }
        }).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/video")
                .setBody("x".repeat(64 * 1024)))
            server.enqueue(response("abcd"))
            server.start()
            SeekPreviewRangeReader(measuredClient, server.url("/redirect").toString(), emptyMap(), maxBytes = 4).use {
                assertEquals(4, it.size())
            }
            assertTrue("Only the four video bytes should be consumed", transferred.get() <= 4L)
            assertEquals(2, server.requestCount)
            server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals("An unconsumed redirect body must not be drained to reuse its connection", 0,
                server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
        }
    }

    @Test
    fun `empty redirect bodies can reuse the connection without spending the byte budget`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/video").setBody(""))
            server.enqueue(response("abcd"))
            server.start()
            SeekPreviewRangeReader(client, server.url("/redirect").toString(), emptyMap(), maxBytes = 4).use {
                assertEquals(4, it.size())
            }
            server.takeRequest(1, TimeUnit.SECONDS)
            assertEquals(1, server.takeRequest(1, TimeUnit.SECONDS)!!.sequenceNumber)
        }
    }

    @Test
    fun `HTTPS redirects cannot downgrade source credentials to HTTP`() {
        val redirectClient = client.newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(302).message("Found").header("Location", "http://example.test/video")
                .body("".toResponseBody()).build()
        }.build()
        SeekPreviewRangeReader(redirectClient, "https://example.test/video", mapOf("Authorization" to "Bearer test")).use { reader ->
            assertEquals("Insecure preview redirect", assertThrows(IOException::class.java) { reader.size() }.message)
        }
    }

    @Test
    fun `cross-origin redirects preserve current source auth without leaking previous source headers`() {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                listOf("Bearer first", "Bearer second", null).forEach { auth ->
                    origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/video")))
                    target.enqueue(response("abcd"))
                    val headers = auth?.let { mapOf("Authorization" to it) }.orEmpty()
                    SeekPreviewRangeReader(client, origin.url("/redirect").toString(), headers).use {
                        assertEquals(4, it.size())
                    }
                    assertEquals(auth, origin.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
                    val redirected = target.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals(auth, redirected.getHeader("Authorization"))
                    assertEquals("bytes=0-131071", redirected.getHeader("Range"))
                    assertEquals("identity", redirected.getHeader("Accept-Encoding"))
                }
            }
        }
    }

    @Test
    fun `later range reads reuse the resolved video URL instead of repeating redirects`() {
        MockWebServer().use { origin ->
            MockWebServer().use { target ->
                origin.start()
                target.start()
                origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/video")))
                target.enqueue(response("abcd", total = 100))
                target.enqueue(response("wxyz", start = 50, total = 100))
                SeekPreviewRangeReader(client, origin.url("/redirect").toString(), mapOf("Authorization" to "Bearer test")).use { reader ->
                    assertEquals(100, reader.size())
                    assertEquals(4, reader.readAt(50, ByteArray(4), 0, 4))
                }
                assertEquals(1, origin.requestCount)
                assertEquals(2, target.requestCount)
                target.takeRequest(1, TimeUnit.SECONDS)
                val nextRange = target.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("bytes=50-99", nextRange.getHeader("Range"))
                assertEquals("Bearer test", nextRange.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun `redirect loops stop after five hops`() {
        MockWebServer().use { server ->
            repeat(7) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop")) }
            server.start()
            SeekPreviewRangeReader(client, server.url("/loop").toString(), emptyMap()).use { reader ->
                assertThrows(IOException::class.java) { reader.size() }
                assertEquals(6, server.requestCount)
            }
        }
    }

    @Test
    fun `redirects share the same extraction deadline`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/video"))
            server.enqueue(response("abcd"))
            server.start()
            var now = 0L
            val slowClient = client.newBuilder().addNetworkInterceptor { chain ->
                chain.proceed(chain.request()).also { now = 2_501L }
            }.build()
            SeekPreviewRangeReader(slowClient, server.url("/redirect").toString(), emptyMap(), nowMs = { now }).use { reader ->
                assertThrows(IOException::class.java) { reader.size() }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `servers ignoring Range are rejected without downloading the file`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("whole file"))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                assertThrows(IOException::class.java) { reader.size() }
            }
        }
    }

    @Test
    fun `invalid and oversized content ranges are rejected`() {
        listOf("bytes 1-3/10", "bytes 0-999999/1000000", "bytes 0-3/*", "bytes 0-3/3").forEach { range ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", range).setBody("abcd"))
                server.start()
                SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                    assertThrows(IOException::class.java) { reader.size() }
                }
            }
        }
    }

    @Test
    fun `a strict byte budget prevents further range requests`() {
        MockWebServer().use { server ->
            server.enqueue(response("abcd", total = 100))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap(), maxBytes = 4).use { reader ->
                assertEquals(100, reader.size())
                assertThrows(IOException::class.java) { reader.readAt(10, ByteArray(1), 0, 1) }
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `random access loads another bounded window`() {
        MockWebServer().use { server ->
            server.enqueue(response("abcd", total = 100))
            server.enqueue(response("wxyz", start = 50, total = 100))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                assertEquals(100, reader.size())
                val data = ByteArray(4)
                assertEquals(4, reader.readAt(50, data, 0, 4))
                assertArrayEquals("wxyz".toByteArray(), data)
                server.takeRequest(1, TimeUnit.SECONDS)
                val second = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("bytes=50-99", second.getHeader("Range"))
                assertEquals(1, second.sequenceNumber) // A valid, fully-read range reuses the connection.
            }
        }
    }

    @Test
    fun `sample reads crossing cached window boundaries are filled completely`() {
        MockWebServer().use { server ->
            val first = "a".repeat(128 * 1024)
            val last = "b".repeat(37)
            val total = (first.length + last.length).toLong()
            server.enqueue(response(first, total = total))
            server.enqueue(response(last, start = first.length.toLong(), total = total))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                assertEquals(total, reader.size())
                val data = ByteArray(6)
                assertEquals(6, reader.readAt(first.length - 2L, data, 0, data.size))
                assertArrayEquals("aabbbb".toByteArray(), data)
                assertEquals(2, server.requestCount)
            }
        }
    }

    @Test
    fun `a read spanning several windows is short only at actual EOF`() {
        MockWebServer().use { server ->
            server.enqueue(response("abcd"))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                val data = ByteArray(10)
                assertEquals(4, reader.readAt(0, data, 0, data.size))
                assertArrayEquals("abcd".toByteArray(), data.copyOf(4))
            }
        }
    }

    @Test
    fun `close aborts an in-flight HTTP call`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val executor = Executors.newSingleThreadExecutor()
            try {
                SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                    val pending = executor.submit<Long> { reader.size() }
                    assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
                    reader.close()
                    val failure = assertThrows(ExecutionException::class.java) { pending.get(2, TimeUnit.SECONDS) }
                    assertTrue(failure.cause is IOException)
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `truncated range bodies fail instead of caching partial data`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-7/8").setBody("abcd"))
            server.start()
            SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap()).use { reader ->
                assertThrows(IOException::class.java) { reader.size() }
            }
        }
    }

    @Test
    fun `deadline and close prevent new requests`() {
        MockWebServer().use { server ->
            server.start()
            var now = 0L
            val reader = SeekPreviewRangeReader(client, server.url("/").toString(), emptyMap(), nowMs = { now })
            now = 2_501L
            assertThrows(IOException::class.java) { reader.size() }
            reader.close()
            assertThrows(IOException::class.java) { reader.readAt(0, ByteArray(1), 0, 1) }
            assertEquals(0, server.requestCount)
        }
    }
}
