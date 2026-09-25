package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SrtCue
import com.nuvio.tv.ui.screens.player.SrtDocument
import com.nuvio.tv.ui.screens.player.SubtitleSyncCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AutoSyncDocumentAdapterTest {
    private val reference = (1..40).map { index ->
        SubtitleSyncCue(index * 4_000L, index * 4_000L + 1_200L, "reference $index")
    }
    private val target = reference.map { cue ->
        cue.copy(startTimeMs = cue.startTimeMs - 2_500L, endTimeMs = cue.endTimeMs - 2_500L)
    }
    private val document = SrtDocument(target.map { cue ->
        SrtCue(cue.startTimeMs, cue.endTimeMs, cue.text)
    })

    @Test
    fun preservesSubtitleTextAndUsesValidatedCueTiming() {
        val timeline = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 2_500.0)
        assertNotNull(timeline)
        val rewritten = retimeSubtitleDocument(document, timeline!!)

        assertEquals(document.cues.map(SrtCue::text), rewritten?.cues?.map(SrtCue::text))
        assertEquals(timeline.cues.map { it.startTimeMs }, rewritten?.cues?.map(SrtCue::startMs))
    }

    @Test
    fun refusesUnmatchedOrUnconfidentTimelines() {
        val timeline = AutoSyncTimelineRetimer.retime(reference, target, 1.0, 2_500.0)
        assertNotNull(timeline)
        assertNull(retimeSubtitleDocument(document, timeline!!.copy(confident = false)))
        val changed = document.copy(cues = document.cues.dropLast(1) +
            document.cues.last().copy(startMs = 999_999L))
        assertNull(retimeSubtitleDocument(changed, timeline))
    }
}
