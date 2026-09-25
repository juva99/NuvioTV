package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SrtDocument
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoSyncRealWorldTest {
    private fun fixture(name: String): SrtDocument {
        val resource = requireNotNull(javaClass.getResource("/subtitle-sync/$name"))
        return SrtDocument.parse(resource.readText())
    }

    private fun SrtDocument.asSyncCues(): List<SubtitleSyncCue> = cues.map {
        SubtitleSyncCue(it.startMs, it.endMs, it.text)
    }

    @Test
    fun correctsOffsetOnRealEnglishHebrewMoviePair() {
        val reference = fixture("real-eng-reference.srt").asSyncCues()
        val target = fixture("real-heb-target.srt")
        val shifted = target.asSyncCues().map { cue ->
            cue.copy(
                startTimeMs = cue.startTimeMs + 2_400L,
                endTimeMs = cue.endTimeMs + 2_400L
            )
        }
        val result = AutoSyncTimelineRetimer.retime(
            reference, shifted, 1.0, 0.0, discoverAlignment = true
        )
        assertNotNull("V2 did not align the real movie pair", result)
        assertTrue("V2 rejected the real movie pair: ${result?.rejectReason}", result!!.confident)
        val medianCorrection = result.cues.map {
            it.startTimeMs - it.originalStartTimeMs
        }.sorted()[result.cues.size / 2]
        assertTrue("Unexpected median correction: $medianCorrection", abs(medianCorrection + 2_400L) < 750L)
    }

    @Test
    fun alignsRealTranslatedEpisode() {
        val reference = fixture("episode-eng-timings.srt").asSyncCues()
        val target = fixture("episode-heb-timings.srt").asSyncCues()
        val result = AutoSyncTimelineRetimer.retime(
            reference, target, 1.0, 0.0, discoverAlignment = true
        )
        assertNotNull("V2 did not align the translated episode", result)
        assertTrue("V2 rejected the translated episode: ${result?.rejectReason}", result!!.confident)
    }

    @Test
    fun alignsLongTranslatedMovieWithTrailingMetadata() {
        val reference = fixture("long-eng-reference-timings.srt").asSyncCues()
        val target = fixture("long-heb-target-timings.srt").asSyncCues()
        val result = AutoSyncTimelineRetimer.retime(
            reference, target, 1.0, 0.0, discoverAlignment = true
        )
        assertNotNull("V2 did not align the long translated movie", result)
        assertTrue("V2 rejected the long movie: ${result?.rejectReason}", result!!.confident)
    }

    @Test
    fun doesNotAcceptUnrelatedRealEmbeddedSubtitle() {
        val reference = fixture("unrelated-embedded.srt").asSyncCues()
        val target = fixture("real-heb-target.srt").asSyncCues()
        val result = AutoSyncTimelineRetimer.retime(
            reference, target, 1.0, 0.0, discoverAlignment = true
        )
        assertTrue("V2 accepted an unrelated subtitle", result?.confident != true)
    }
}
