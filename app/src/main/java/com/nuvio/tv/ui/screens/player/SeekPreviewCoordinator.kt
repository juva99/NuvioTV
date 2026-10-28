package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class SeekPreviewSource(
    val url: String,
    val headers: Map<String, String>,
    val generation: Long
)

internal data class SeekPreviewState<T>(
    val positionMs: Long? = null,
    val frame: T? = null,
    val isLoading: Boolean = false,
    val sourceGeneration: Long = 0L
)

/** Main-thread coordinator. Native extraction is deliberately never cancelled/replaced by a key repeat. */
internal class SeekPreviewCoordinator<T : Any>(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val extract: suspend (SeekPreviewSource, Long) -> T?,
    private val cancelRead: () -> Unit,
    private val sizeBytes: (T) -> Int,
    private val maxCacheBytes: Int = 8 * 1024 * 1024
) {
    private val _state = MutableStateFlow(SeekPreviewState<T>())
    val state = _state.asStateFlow()
    private var source: SeekPreviewSource? = null
    private var pendingPositionMs: Long? = null
    private var targetMs: Long? = null
    private var allowed = false
    private var revision = 0L
    private var worker: Job? = null
    private var committedPositionMs: Long? = null
    private var hideCommittedPreview: Job? = null
    private var closed = false
    private var holdStartedMs = 0L
    private var failures = 0
    private var cooldownUntilMs = 0L
    private var nextExtractionMs = 0L
    private var cachedBytes = 0
    private val cache = LinkedHashMap<Long, T>(16, 0.75f, true)

    fun update(source: SeekPreviewSource?, positionMs: Long?, allowExtraction: Boolean) {
        if (closed) return
        pendingPositionMs = positionMs
        val newInput = positionMs != null && positionMs != committedPositionMs
        if (newInput && committedPositionMs != null) holdStartedMs = nowMs()
        if (this.source != source || newInput) {
            committedPositionMs = null
            hideCommittedPreview?.cancel()
        }
        updateTarget(source, positionMs ?: committedPositionMs, allowExtraction)
    }

    private fun updateTarget(source: SeekPreviewSource?, positionMs: Long?, allowExtraction: Boolean) {
        if (this.source != source || (targetMs == null && positionMs != null)) holdStartedMs = nowMs()
        if (this.source != source) {
            cancelRead()
            revision++
            this.source = source
            cache.clear()
            cachedBytes = 0
            failures = 0
            cooldownUntilMs = 0L
        }
        if ((targetMs != null && positionMs == null) || (allowed && !allowExtraction)) {
            cancelRead()
            revision++
            if (allowed && !allowExtraction && positionMs != null && committedPositionMs == null) {
                cooldownUntilMs = nowMs() + COOLDOWN_MS
            }
        }
        targetMs = positionMs?.coerceAtLeast(0L)
        allowed = allowExtraction
        publish()
        if (source != null && targetMs != null && worker == null && canExtract() &&
            !cache.containsKey(bucketMs(targetMs!!))
        ) {
            worker = scope.launch {
                try {
                    runWorker()
                } finally {
                    worker = null
                }
            }
        }
    }

    private fun canExtract(): Boolean = allowed && failures < MAX_FAILURES && nowMs() >= cooldownUntilMs

    private fun publish() {
        val target = targetMs
        val frame = target?.let { cache[bucketMs(it)] }
        // Unsupported, unhealthy, or cooled-down sources retain the existing timestamp-only UI.
        _state.value = if (source == null || target == null ||
            (frame == null && !canExtract())
        ) {
            SeekPreviewState()
        } else {
            SeekPreviewState(target, frame, frame == null, source?.generation ?: 0L)
        }
    }

    private suspend fun runWorker() {
        while (!closed && source != null && targetMs != null && canExtract()) {
            val bucket = bucketMs(targetMs!!)
            if (cache.containsKey(bucket)) return
            // A new gesture must not inherit a released gesture's timer or native work.
            val waitMs = maxOf(nextExtractionMs, holdStartedMs + INITIAL_DELAY_MS) - nowMs()
            if (waitMs > 0) {
                delay(waitMs)
                continue // Re-read the latest position, never a queue of intermediate targets.
            }
            val requestSource = source!!
            val requestRevision = revision
            publish()
            val startedMs = nowMs()
            val frame = try {
                extract(requestSource, bucket)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val elapsedMs = nowMs() - startedMs
            nextExtractionMs = nowMs() + EXTRACTION_INTERVAL_MS
            if (requestSource != source || requestRevision != revision || closed) continue
            val cached = frame?.let { putCached(bucket, it) } ?: false
            if (!cached || elapsedMs > SLOW_EXTRACTION_MS) {
                failures++
                cooldownUntilMs = nowMs() + COOLDOWN_MS
            }
            // publish() looks up the current bucket: stale images can be cached but never displayed.
            publish()
        }
    }

    private fun putCached(bucket: Long, frame: T): Boolean {
        val bytes = sizeBytes(frame).coerceAtLeast(0)
        if (bytes > maxCacheBytes) return false
        cache.put(bucket, frame)?.let { cachedBytes -= sizeBytes(it) }
        cachedBytes += bytes
        val iterator = cache.entries.iterator()
        while (cachedBytes > maxCacheBytes && iterator.hasNext()) {
            cachedBytes -= sizeBytes(iterator.next().value)
            iterator.remove()
        }
        // Do not recycle evicted bitmaps: Compose may still be drawing one.
        return true
    }

    fun commit(positionMs: Long? = targetMs) {
        if (closed || (positionMs != null && source == null)) return
        if (positionMs != null && (targetMs == null || committedPositionMs != null)) holdStartedMs = nowMs()
        committedPositionMs = positionMs
        hideCommittedPreview?.cancel()
        updateTarget(source, positionMs, allowed)
        if (positionMs == null) return
        hideCommittedPreview = scope.launch {
            delay(POST_COMMIT_PREVIEW_MS)
            committedPositionMs = null
            updateTarget(source, pendingPositionMs, allowed)
        }
    }

    fun close() {
        closed = true
        hideCommittedPreview?.cancel()
        cancelRead()
        revision++
        cache.clear()
        cachedBytes = 0
        _state.value = SeekPreviewState()
        worker?.cancel()
    }

    companion object {
        const val INITIAL_DELAY_MS = 180L
        const val EXTRACTION_INTERVAL_MS = 500L
        const val POST_COMMIT_PREVIEW_MS = 3_000L
        const val SLOW_EXTRACTION_MS = 1_500L
        const val COOLDOWN_MS = 15_000L
        const val MAX_FAILURES = 3
        fun bucketMs(positionMs: Long): Long = positionMs.coerceAtLeast(0L) / 10_000L * 10_000L
    }
}
