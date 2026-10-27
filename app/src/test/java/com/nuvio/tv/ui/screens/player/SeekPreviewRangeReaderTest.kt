package com.nuvio.tv.ui.screens.player

import okhttp3.OkHttpClient
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
