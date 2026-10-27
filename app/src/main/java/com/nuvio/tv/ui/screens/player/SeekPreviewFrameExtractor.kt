package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import com.nuvio.tv.NuvioApplication
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException

internal class SeekPreviewFrameExtractor {
    @Volatile private var reader: SeekPreviewRangeReader? = null
    private val cancellationVersion = AtomicLong()

    private val client by lazy {
        PlayerPlaybackNetworking.createHttpClient().newBuilder()
            // The playback SSL fallback creates a separate call with its own timeouts. Previews
            // must fail closed instead of escaping their network deadline on that fallback path.
            .apply { interceptors().clear() }
            .cookieJar(NuvioApplication.extensionCookieJar)
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    suspend fun extract(source: SeekPreviewSource, positionMs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null
        val version = cancellationVersion.get()
        return withContext(decoderDispatcher) {
            if (version != cancellationVersion.get()) null else extractScaled(source, positionMs, version)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun extractScaled(source: SeekPreviewSource, positionMs: Long, version: Long): Bitmap? {
        val input = SeekPreviewRangeReader(client, source.url, source.headers)
        reader = input
        if (version != cancellationVersion.get()) input.close()
        val readFailure = AtomicReference<Exception?>()
        fun <T> read(block: () -> T): T = try {
            block()
        } catch (e: Exception) {
            readFailure.set(e)
            throw e
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(object : MediaDataSource() {
                override fun getSize(): Long = read { input.size() }
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    read { input.readAt(position, buffer, offset, size) }
                override fun close() = input.close()
            })
            retriever.getScaledFrameAtTime(
                positionMs * 1_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                320,
                180
            ).also { frame ->
                if (frame == null) readFailure.get()?.let { throw it }
            }
        } catch (_: OutOfMemoryError) {
            // Some platform codecs allocate a full-resolution intermediate even for scaled output.
            null
        } catch (e: RuntimeException) {
            // MediaMetadataRetriever otherwise hides a failed read behind an opaque native status.
            throw IOException("Platform seek-preview extraction failed", readFailure.get() ?: e)
        } finally {
            input.close()
            runCatching { retriever.release() }
            reader = null
        }
    }

    fun cancelRead() {
        cancellationVersion.incrementAndGet()
        reader?.close()
    }

    companion object {
        // Process-wide: a slow native call from an exited player cannot overlap a new player's decode.
        // Coroutines cannot forcibly interrupt native codec work. Never spawn a replacement thread.
        private val decoderDispatcher by lazy {
            Executors.newSingleThreadExecutor { runnable ->
                Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    runnable.run()
                }, "seek-preview").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }
}
