package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor
import com.nuvio.tv.core.player.dvmkv.TrackAwareSeekMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.EOFException
import java.util.concurrent.atomic.AtomicReference

internal sealed interface SubtitleReferenceScanResult {
    data class Indexed(val cueCount: Int) : SubtitleReferenceScanResult
    data object Unsupported : SubtitleReferenceScanResult
    data object IndexUnavailable : SubtitleReferenceScanResult
    data object TimedOut : SubtitleReferenceScanResult
}

internal data class SubtitleContentRange(
    val start: Long,
    val end: Long,
    val totalLength: Long?
)

private val CONTENT_RANGE_PATTERN = Regex(
    "bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)",
    RegexOption.IGNORE_CASE
)

internal fun parseSubtitleContentRange(header: String?): SubtitleContentRange? {
    val match = CONTENT_RANGE_PATTERN.matchEntire(header?.trim() ?: return null) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (start > end) return null

    val totalText = match.groupValues[3]
    val totalLength = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
    if (totalLength != null && (totalLength <= end || totalLength <= 0L)) return null

    return SubtitleContentRange(start, end, totalLength)
}

@UnstableApi
internal class SubtitleReferenceScanner(
    private val context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val store: SubtitleReferenceCueStore
) : Closeable {
    private val lifecycleLock = Any()
    private val activeDataSource = AtomicReference<DataSource?>()
    private val captureGeneration = store.currentGeneration()
    @Volatile private var closed = false
    private var bytesRead = 0L
    private var streamLength = C.LENGTH_UNSET.toLong()
    private var reopenCount = 0

    suspend fun scan(): SubtitleReferenceScanResult = try {
        withTimeout(MAX_SCAN_TIME_MS) {
            runInterruptible(Dispatchers.IO) { scanBlocking() }
        }
    } catch (_: TimeoutCancellationException) {
        close()
        SubtitleReferenceScanResult.TimedOut
    } catch (cancellation: CancellationException) {
        close()
        throw cancellation
    }

    override fun close() {
        val source = synchronized(lifecycleLock) {
            closed = true
            activeDataSource.getAndSet(null)
        }
        runCatching { source?.close() }
    }

    private fun scanBlocking(): SubtitleReferenceScanResult {
        if (!supportsRangeRequests()) return SubtitleReferenceScanResult.Unsupported

        val extractor = MatroskaExtractor(DefaultSubtitleParserFactory())
        val output = IndexedSubtitleExtractorOutput(store, captureGeneration)
        var sourceAndInput: SourceAndInput? = null
        try {
            sourceAndInput = openInput(0L)
            var currentSourceAndInput = sourceAndInput!!
            val sniffed = try {
                extractor.sniff(currentSourceAndInput.input)
            } catch (_: EOFException) {
                false
            } finally {
                currentSourceAndInput.input.resetPeekPosition()
            }
            if (!sniffed) return SubtitleReferenceScanResult.Unsupported

            extractor.init(output)
            val seekPosition = PositionHolder()
            while (!closed && bytesRead < MAX_SCAN_BYTES) {
                val result = extractor.read(currentSourceAndInput.input, seekPosition)
                val indexed = output.indexedCues()
                if (indexed > 0) return SubtitleReferenceScanResult.Indexed(indexed)
                when (result) {
                    Extractor.RESULT_END_OF_INPUT -> return SubtitleReferenceScanResult.IndexUnavailable
                    Extractor.RESULT_SEEK -> {
                        if (seekPosition.position < 0L || ++reopenCount > MAX_REOPENS) {
                            return SubtitleReferenceScanResult.IndexUnavailable
                        }
                        currentSourceAndInput.close()
                        sourceAndInput = null
                        currentSourceAndInput = openInput(seekPosition.position)
                        sourceAndInput = currentSourceAndInput
                    }
                }
            }
            return SubtitleReferenceScanResult.IndexUnavailable
        } catch (_: InvalidSubtitleRangeResponseException) {
            return SubtitleReferenceScanResult.IndexUnavailable
        } finally {
            sourceAndInput?.close()
            extractor.release()
        }
    }

    private fun supportsRangeRequests(): Boolean {
        val connection = PlayerPlaybackNetworking.openConnection(
            url = url,
            headers = PlayerMediaSourceFactory.sanitizeHeaders(headers),
            method = "GET",
            connectTimeoutMs = 5_000,
            readTimeoutMs = 5_000,
            range = "bytes=0-0"
        )
        return try {
            connection.connect()
            connection.responseCode == 206
        } finally {
            connection.disconnect()
        }
    }

    private fun openInput(position: Long): SourceAndInput {
        val source = PlayerPlaybackNetworking.createDataSourceFactory(
            context,
            PlayerMediaSourceFactory.sanitizeHeaders(headers)
        ).createDataSource()
        synchronized(lifecycleLock) {
            check(!closed)
            activeDataSource.set(source)
        }
        var handedOff = false
        try {
            val openedLength = source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse(url))
                    .setPosition(position)
                    .setLength((MAX_SCAN_BYTES - bytesRead).coerceAtLeast(1L))
                    .build()
            )
            val contentRange = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
            val parsedContentRange = parseSubtitleContentRange(contentRange)
            if (parsedContentRange == null || parsedContentRange.start != position) {
                throw InvalidSubtitleRangeResponseException(
                    "Expected Content-Range starting at $position, got ${contentRange ?: "<missing>"}"
                )
            }
            val returnedRangeLength = parsedContentRange.end - parsedContentRange.start + 1L
            if (returnedRangeLength <= 0L ||
                (openedLength != C.LENGTH_UNSET.toLong() && openedLength != returnedRangeLength)
            ) {
                throw InvalidSubtitleRangeResponseException(
                    "Content-Range length does not match response for position $position"
                )
            }
            parsedContentRange.totalLength?.let { streamLength = it }
            check(!closed)
            val countingReader = androidx.media3.common.DataReader { buffer, offset, length ->
                if (closed || bytesRead >= MAX_SCAN_BYTES) return@DataReader C.RESULT_END_OF_INPUT
                val allowed = minOf(length.toLong(), MAX_SCAN_BYTES - bytesRead).toInt()
                val read = source.read(buffer, offset, allowed)
                if (read > 0) bytesRead += read
                read
            }
            val sourceAndInput = SourceAndInput(
                source = source,
                input = DefaultExtractorInput(countingReader, position, streamLength),
                onClose = { activeDataSource.compareAndSet(source, null) }
            )
            handedOff = true
            return sourceAndInput
        } finally {
            if (!handedOff) {
                activeDataSource.compareAndSet(source, null)
                runCatching { source.close() }
            }
        }
    }

    private class SourceAndInput(
        private val source: DataSource,
        val input: DefaultExtractorInput,
        private val onClose: () -> Unit
    ) : Closeable {
        override fun close() {
            onClose()
            runCatching { source.close() }
        }
    }

    private companion object {
        const val MAX_SCAN_TIME_MS = 8_000L
        const val MAX_SCAN_BYTES = 16L * 1024L * 1024L
        const val MAX_REOPENS = 4
    }

    private class InvalidSubtitleRangeResponseException(message: String) : Exception(message)
}

@UnstableApi
private class IndexedSubtitleExtractorOutput(
    private val store: SubtitleReferenceCueStore,
    private val captureGeneration: Long
) : ExtractorOutput {
    private val eligibleTracks = mutableMapOf<Int, IndexedTrack>()
    private var seekMap: SeekMap? = null

    override fun track(id: Int, type: Int): TrackOutput {
        val discard = DiscardingTrackOutput()
        if (type != C.TRACK_TYPE_TEXT) return discard
        return object : ForwardingTrackOutput(discard) {
            override fun format(format: Format) {
                if (format.isEligibleEnglishSubtitleReference()) {
                        store.register(format, captureGeneration)?.let { trackKey ->
                        eligibleTracks[id] = IndexedTrack(trackKey, format.subtitleSourceMimeType())
                    }
                }
                super.format(format)
            }
        }
    }

    override fun endTracks() = Unit

    override fun seekMap(seekMap: SeekMap) {
        this.seekMap = seekMap
    }

    fun indexedCues(): Int {
        val trackMap = seekMap as? TrackAwareSeekMap ?: return 0
        var total = 0
        eligibleTracks.forEach { (trackId, track) ->
            val timesUs = trackMap.getCueTimesUs(trackId)
            // Track-aware Matroska cue points are timestamps for indexed clusters, not PGS packet
            // classifications. Do not infer display/clear parity from their array index.
            val timingCues = timesUs.asList()
            // Added in one batch: publishing a status per cue meant thousands of UI state copies
            // in a tight loop while video was decoding.
            store.addCues(
                track.key,
                timingCues.map { timeUs ->
                    val startMs = timeUs / 1000L
                    SrtCue(startMs, startMs + 1_000L, " ")
                }
            )
            total = maxOf(total, timingCues.size)
        }
        return total
    }

    private data class IndexedTrack(val key: String, val sourceMimeType: String?)
}
