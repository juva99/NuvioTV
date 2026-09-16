package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SubtitleEpisodeSyncRegressionTest {
    @Test
    fun `translated episode does not turn adjacent chance matches into edit regions`() {
        val reference = fixture("episode-eng-timings.srt")
        val target = fixture("episode-heb-timings.srt")
        assertEquals(670, reference.cues.size)
        assertEquals(513, target.cues.size)

        // Preserve the measured cue segmentation and metadata, not the dialogue text.
        // Moving the entire target also guards against special-casing a near-zero offset.
        for (shiftMs in listOf(0L, 12_000L, -4_000L)) {
            val shifted = SrtDocument(target.cues.map {
                it.copy(startMs = it.startMs + shiftMs, endMs = it.endMs + shiftMs)
            })
            val plan = requireNotNull(SubtitleRateAwareAligner.align(reference.cues, shifted.cues))
            assertEquals(1.0, plan.rateRatio, 0.0)
            assertEquals("invented edits: ${plan.model.segments}", 1, plan.model.segments.size)
            val offsetMs = plan.model.segments.single().offsetMs
            assertTrue("unexpected correction $offsetMs", abs(offsetMs + shiftMs - 850L) <= 250L)
            val rewritten = plan.rewrite(shifted)
            assertEquals(shifted.cues.size, rewritten.cues.size)
            assertTrue(rewritten.cues.zip(shifted.cues).all { (after, before) ->
                after.startMs - before.startMs == offsetMs &&
                    after.endMs - before.endMs == offsetMs && after.text == before.text
            })
        }
    }

    @Test
    fun `translated episode still preserves a sustained inserted section`() {
        val reference = fixture("episode-eng-timings.srt")
        val target = fixture("episode-heb-timings.srt")
        val editMs = 790_000L // A dialogue gap in both tracks.
        val insertionMs = 60_000L
        val editedReference = reference.cues.map { cue ->
            if (cue.startMs < editMs) cue else cue.copy(
                startMs = cue.startMs + insertionMs,
                endMs = cue.endMs + insertionMs
            )
        }
        val plan = requireNotNull(SubtitleRateAwareAligner.align(editedReference, target.cues))
        assertEquals(1.0, plan.rateRatio, 0.0)
        assertTrue(plan.model.segments.any { abs(it.offsetMs - 850L) <= 250L })
        assertTrue(plan.model.segments.any { abs(it.offsetMs - insertionMs - 850L) <= 250L })
    }

    private fun fixture(name: String): SrtDocument =
        SrtDocument.parse(requireNotNull(javaClass.getResource("/subtitle-sync/$name")).readText())
}
