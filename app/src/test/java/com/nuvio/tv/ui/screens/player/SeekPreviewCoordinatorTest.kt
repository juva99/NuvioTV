package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeekPreviewCoordinatorTest {
    private val source = SeekPreviewSource("https://example.test/video.mkv", emptyMap(), 1)

    private fun TestScope.coordinator(
        maxBytes: Int = 100,
        cancelRead: () -> Unit = {},
        extract: suspend (SeekPreviewSource, Long) -> String? = { _, bucket -> "$bucket" }
    ) = SeekPreviewCoordinator(
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        extract = extract,
        cancelRead = cancelRead,
        sizeBytes = { _: String -> 10 },
        maxCacheBytes = maxBytes
    )

    @Test
    fun `cancelling an uncommitted input prevents extraction`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 15_000, true)
        runCurrent()
        advanceTimeBy(100)
        coordinator.update(source, null, true)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(0, calls)
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `consecutive uncommitted inputs each get their own extraction delay`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(100)
        coordinator.update(source, null, true)
        advanceTimeBy(50)
        coordinator.update(source, 20_000, true)
        advanceTimeBy(30)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(20_000L, coordinator.state.value.positionMs)
        assertTrue(coordinator.state.value.isLoading)
        advanceTimeBy(149)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `new taps show loading immediately but still get their own extraction delay`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(100)
        coordinator.update(source, null, true)
        advanceTimeBy(100)
        runCurrent()
        coordinator.update(source, 20_000, true)
        assertEquals(20_000L, coordinator.state.value.positionMs)
        assertTrue(coordinator.state.value.isLoading)
        advanceTimeBy(179)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `loading state identifies the source so UI cannot retain another source image`() = runTest {
        val coordinator = coordinator()
        coordinator.update(source, 10_000, true)
        val previous = coordinator.state.value
        coordinator.update(source.copy(generation = 2), 10_000, true)
        assertEquals(1L, previous.sourceGeneration)
        assertEquals(2L, coordinator.state.value.sourceGeneration)
        assertTrue(previous != coordinator.state.value)
    }

    @Test
    fun `quick tap can load a preview after commit and it expires automatically`() = runTest {
        val coordinator = coordinator()
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(50)
        coordinator.commit()
        coordinator.update(source, null, true)
        assertEquals(10_000L, coordinator.state.value.positionMs)
        advanceTimeBy(130)
        runCurrent()
        assertEquals("10000", coordinator.state.value.frame)
        coordinator.update(source, null, true) // Periodic playback refresh must retain the committed target.
        advanceTimeBy(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS)
        runCurrent()
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `direct skips can request the same post-commit preview without a pending hold`() = runTest {
        val coordinator = coordinator()
        coordinator.update(source, null, true)
        coordinator.commit(10_000)
        coordinator.update(source, null, true)
        advanceTimeBy(180)
        runCurrent()
        assertEquals("10000", coordinator.state.value.frame)
    }

    @Test
    fun `new gesture cancels the previous expiry and gets a fresh hold timer`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        coordinator.commit()
        coordinator.update(source, null, true)
        advanceTimeBy(1_000)
        runCurrent()
        coordinator.update(source, 20_000, true)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(179)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, calls)
        advanceTimeBy(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS)
        runCurrent()
        assertEquals(20_000L, coordinator.state.value.positionMs)
    }

    @Test
    fun `a held input at the same clamped position survives the old committed preview expiry`() = runTest {
        val coordinator = coordinator()
        coordinator.update(source, 0, true)
        coordinator.commit()
        coordinator.update(source, null, true)
        advanceTimeBy(1_000)
        runCurrent()
        coordinator.update(source, 0, true)
        advanceTimeBy(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS)
        runCurrent()
        assertEquals(0L, coordinator.state.value.positionMs)
        coordinator.commit()
        coordinator.update(source, null, true)
        advanceTimeBy(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS)
        runCurrent()
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `expected post-seek buffering pauses extraction until playback has recovered`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        coordinator.commit()
        coordinator.update(source, 10_000, false) // Seek callback may arrive before key-up clears the pending value.
        coordinator.update(source, null, false)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(0, calls)
        coordinator.update(source, null, true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals("frame", coordinator.state.value.frame)
    }

    @Test
    fun `expiry cancels an unfinished committed preview and rejects a late frame`() = runTest {
        val result = CompletableDeferred<String>()
        var cancellations = 0
        val coordinator = coordinator(cancelRead = { cancellations++ }) { _, _ -> result.await() }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        coordinator.commit()
        coordinator.update(source, null, true)
        val beforeExpiry = cancellations
        advanceTimeBy(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS)
        runCurrent()
        assertTrue(cancellations > beforeExpiry)
        result.complete("late")
        runCurrent()
        assertNull(coordinator.state.value.positionMs)
        assertNull(coordinator.state.value.frame)
    }

    @Test
    fun `source changes and explicit dismissal clear the retained preview`() = runTest {
        val coordinator = coordinator()
        coordinator.update(source, 10_000, true)
        coordinator.commit()
        coordinator.update(source, null, true)
        coordinator.update(source.copy(generation = 2), null, true)
        assertNull(coordinator.state.value.positionMs)
        coordinator.update(source, 10_000, true)
        coordinator.commit()
        coordinator.commit(null)
        coordinator.update(source, null, true)
        advanceTimeBy(500)
        runCurrent()
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `continuous hold samples latest target without overlapping or queuing`() = runTest {
        val calls = mutableListOf<Long>()
        val first = CompletableDeferred<String>()
        val coordinator = coordinator { _, bucket ->
            calls += bucket
            if (calls.size == 1) first.await() else "latest"
        }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        repeat(30) { coordinator.update(source, 20_000L + it * 10_000L, true) }
        assertEquals(listOf(10_000L), calls)
        first.complete("old")
        runCurrent()
        assertNull(coordinator.state.value.frame)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(listOf(10_000L), calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(10_000L, 310_000L), calls)
        assertEquals("latest", coordinator.state.value.frame)
    }

    @Test
    fun `cache hit is immediate and does not decode again`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "cached" }
        coordinator.update(source, 12_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        coordinator.update(source, null, true)
        coordinator.update(source, 19_000, true)
        assertEquals("cached", coordinator.state.value.frame)
        assertEquals(19_000L, coordinator.state.value.positionMs)
        assertFalse(coordinator.state.value.isLoading)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `source change rejects old results even for the same timestamp`() = runTest {
        val first = CompletableDeferred<String>()
        var calls = 0
        val coordinator = coordinator { _, _ ->
            calls++
            if (calls == 1) first.await() else "new-stream"
        }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        coordinator.update(source.copy(generation = 2), 10_000, true)
        first.complete("old-stream")
        runCurrent()
        assertNull(coordinator.state.value.frame)
        advanceTimeBy(750)
        runCurrent()
        assertEquals("new-stream", coordinator.state.value.frame)
    }

    @Test
    fun `stopping seeking cancels reads and suppresses a late image`() = runTest {
        val result = CompletableDeferred<String>()
        var cancellations = 0
        val coordinator = coordinator(cancelRead = { cancellations++ }) { _, _ -> result.await() }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        val beforeStop = cancellations
        coordinator.update(source, null, true)
        result.complete("late")
        runCurrent()
        assertTrue(cancellations > beforeStop)
        assertNull(coordinator.state.value.frame)
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `buffer pressure aborts work and imposes cooldown`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        coordinator.update(source, 10_000, false)
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, calls)
        assertNull(coordinator.state.value.positionMs)
        advanceTimeBy(15_000)
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun `three failures disable extraction for the source session`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; null }
        repeat(4) {
            coordinator.update(source, 10_000, true)
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            advanceTimeBy(16_000)
        }
        assertEquals(3, calls)
        assertNull(coordinator.state.value.positionMs)
        coordinator.update(source.copy(generation = 2), 10_000, true)
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(4, calls)
    }

    @Test
    fun `slow successful extraction triggers cooldown for uncached positions`() = runTest {
        val result = CompletableDeferred<String>()
        val coordinator = coordinator { _, _ -> result.await() }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        advanceTimeBy(1_600)
        result.complete("slow")
        runCurrent()
        assertEquals("slow", coordinator.state.value.frame)
        coordinator.update(source, 30_000, true)
        assertNull(coordinator.state.value.positionMs)
        coordinator.update(source, 10_000, false)
        assertEquals("slow", coordinator.state.value.frame) // Cached frames cost no network/decoding.
    }

    @Test
    fun `LRU cache stays within byte budget`() = runTest {
        val calls = mutableListOf<Long>()
        val coordinator = coordinator(maxBytes = 20) { _, bucket -> calls += bucket; "$bucket" }
        suspend fun seek(position: Long) {
            coordinator.update(source, position, true)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
        }
        seek(10_000)
        seek(20_000)
        coordinator.update(source, 10_000, true) // Make 20s the least recently used entry.
        seek(30_000)
        coordinator.update(source, 10_000, true)
        assertEquals("10000", coordinator.state.value.frame)
        seek(20_000)
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 20_000L), calls)
    }

    @Test
    fun `oversized platform frames fall back instead of repeatedly decoding the same target`() = runTest {
        var calls = 0
        val coordinator = coordinator(maxBytes = 5) { _, _ -> calls++; "too large" }
        coordinator.update(source, 10_000, true)
        runCurrent()
        advanceTimeBy(180)
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, calls)
        assertNull(coordinator.state.value.positionMs)
    }

    @Test
    fun `close suppresses all future requests`() = runTest {
        var calls = 0
        val coordinator = coordinator { _, _ -> calls++; "frame" }
        coordinator.update(source, 10_000, true)
        runCurrent()
        coordinator.commit()
        coordinator.close()
        coordinator.update(source, 20_000, true)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, calls)
        assertNull(coordinator.state.value.positionMs)
    }
}
