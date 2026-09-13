package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SubtitleSyncPlaybackSourceTest {

    @Test
    fun `synchronized rewrite generation isolates manual cue cache`() {
        val trackKey = "subtitle-id|https://example.test/subtitle.srt"

        assertEquals(trackKey, SubtitleSyncPlaybackSource.cacheKey(trackKey, null))
        assertNotEquals(
            SubtitleSyncPlaybackSource.cacheKey(trackKey, "content://example/synced-1.srt"),
            SubtitleSyncPlaybackSource.cacheKey(trackKey, "content://example/synced-2.srt")
        )
    }

    @Test
    fun `manual cues use rewritten timeline timestamps and text`() {
        val rewritten = SrtDocument(
            cues = listOf(
                SrtCue(startMs = 1_240L, endMs = 2_860L, text = "first"),
                SrtCue(startMs = 9_500L, endMs = 10_100L, text = "second")
            )
        )

        assertEquals(
            listOf(
                SubtitleSyncCue(1_240L, 2_860L, "first"),
                SubtitleSyncCue(9_500L, 10_100L, "second")
            ),
            SubtitleSyncPlaybackSource.toUiCues(rewritten)
        )
    }
}
