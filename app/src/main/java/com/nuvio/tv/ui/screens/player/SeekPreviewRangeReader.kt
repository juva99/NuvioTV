package com.nuvio.tv.ui.screens.player

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A bounded random-access reader, not a downloader. Servers that ignore Range are rejected. */
internal class SeekPreviewRangeReader(
    client: OkHttpClient,
    private var url: String,
    private val headers: Map<String, String>,
    private val maxBytes: Int = 6 * 1024 * 1024,
    private val timeoutMs: Long = 2_500L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : Closeable {
    // Automatic redirects may drain intermediate bodies outside our payload budget.
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val startedMs = nowMs()
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null
    private var length = -1L
    private var bytesRead = 0
    private var windowStart = -1L
    private var window = ByteArray(0)

    @Synchronized
    fun size(): Long {
        if (length < 0L) loadWindow(0L)
        return length
    }

    @Synchronized
    fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        checkBudget()
        if (position < 0L || offset < 0 || size < 0 || offset > buffer.size - size) {
            throw IOException("Invalid preview read")
        }
        if (size == 0) return 0
        if (length >= 0L && position >= length) return -1
        var copied = 0
        var cursor = position
        // Native extractors can request an entire compressed sample across window boundaries.
        // A short read there may be interpreted as a truncated sample, not a request to retry.
        while (copied < size && (length < 0L || cursor < length)) {
            checkBudget()
            if (cursor < windowStart || cursor >= windowStart + window.size) loadWindow(cursor)
            val index = (cursor - windowStart).toInt()
            val count = minOf(size - copied, window.size - index)
            if (count <= 0) break
            window.copyInto(buffer, offset + copied, index, index + count)
            copied += count
            cursor += count
        }
        return if (copied == 0) -1 else copied
    }

    private fun checkBudget(): Long {
        val remainingMs = timeoutMs - (nowMs() - startedMs)
        if (closed || remainingMs <= 0L) throw IOException("Preview read cancelled or timed out")
        return remainingMs
    }

    private fun loadWindow(position: Long) {
        checkBudget()
        val count = minOf(WINDOW_BYTES, maxBytes - bytesRead)
        if (count <= 0) throw IOException("Preview byte budget exceeded")
        val end = minOf(position + count - 1L, if (length > 0L) length - 1L else Long.MAX_VALUE)
        var request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) ->
                if (!key.equals("Range", true) && !key.equals("Accept-Encoding", true)) header(key, value)
            }
            header("Range", "bytes=$position-$end")
            header("Accept-Encoding", "identity")
        }.build()
        repeat(MAX_REDIRECTS + 1) {
            val call = client.newCall(request)
            call.timeout().timeout(checkBudget(), TimeUnit.MILLISECONDS)
            activeCall = call
            var fullyRead = false
            try {
                if (closed) throw IOException("Preview read cancelled")
                val response = call.execute()
                try {
                    checkBudget()
                    if (response.code in REDIRECT_CODES) {
                        fullyRead = response.body?.contentLength() == 0L
                        val next = response.header("Location")?.let { response.request.url.resolve(it) }
                            ?: throw IOException("Invalid preview redirect")
                        if (response.request.url.isHttps && !next.isHttps) throw IOException("Insecure preview redirect")
                        // Retain this source's headers, including playback's cross-origin auth policy.
                        request = request.newBuilder().url(next).build()
                        return@repeat
                    }
                    val range = response.header("Content-Range")?.let(CONTENT_RANGE::matchEntire)
                    val start = range?.groupValues?.get(1)?.toLongOrNull()
                    val last = range?.groupValues?.get(2)?.toLongOrNull()
                    val total = range?.groupValues?.get(3)?.toLongOrNull()
                    if (response.code != 206 || start != position || last == null || total == null ||
                        last < position || last > end || total <= last || (length >= 0 && length != total) ||
                        response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true
                    ) throw IOException("Preview requires valid byte-range support")
                    length = total
                    val expected = (last - position + 1L).toInt()
                    val body = response.body ?: throw IOException("Empty preview response")
                    val data = ByteArray(expected)
                    val input = body.byteStream()
                    var copied = 0
                    while (copied < expected) {
                        checkBudget()
                        val read = input.read(data, copied, expected - copied)
                        if (read < 0) throw IOException("Truncated preview response")
                        copied += read
                        bytesRead += read
                    }
                    windowStart = position
                    window = data
                    url = response.request.url.toString()
                    fullyRead = body.contentLength() == expected.toLong()
                    return
                } finally {
                    // Cancel before close: never drain redirect, invalid, or oversized bodies.
                    if (!fullyRead) call.cancel()
                    response.close()
                }
            } finally {
                if (!fullyRead) call.cancel()
                activeCall = null
            }
        }
        throw IOException("Too many preview redirects")
    }

    override fun close() {
        closed = true
        activeCall?.cancel()
    }

    companion object {
        private const val WINDOW_BYTES = 128 * 1024
        private const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    }
}
