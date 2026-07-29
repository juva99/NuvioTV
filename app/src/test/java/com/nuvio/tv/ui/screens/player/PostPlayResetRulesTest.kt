package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayResetRulesTest {

    private val nextInfo = NextEpisodeInfo(
        videoId = "v1",
        season = 1,
        episode = 2,
        title = "Ep",
        thumbnail = null,
        overview = null,
        released = null,
        hasAired = true,
        unairedMessage = null,
    )

    @Test
    fun `ended playback does not reset while next episode autoplay search is active`() {
        val state = PlayerUiState(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = nextInfo,
                searching = true,
            )
        )

        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = state,
                hasInFlightNextEpisodeAutoPlay = true
            )
        )
    }

    @Test
    fun `ended playback does not reset during next episode autoplay countdown`() {
        val state = PlayerUiState(
            postPlayMode = PostPlayMode.AutoPlay(
                nextEpisode = nextInfo,
                countdownSec = 2,
            )
        )

        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = state,
                hasInFlightNextEpisodeAutoPlay = true
            )
        )
    }

    @Test
    fun `ended playback resets when no post play flow is active`() {
        assertTrue(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(),
                hasInFlightNextEpisodeAutoPlay = false
            )
        )
    }

    @Test
    fun `ended playback does not reset while still watching prompt is active`() {
        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(
                    postPlayMode = PostPlayMode.StillWatching(nextEpisode = nextInfo)
                ),
                hasInFlightNextEpisodeAutoPlay = false
            )
        )
    }

    @Test
    fun `ended signal before first frame of the new stream is ignored`() {
        // Stale completion tick from the previous episode while an auto-play
        // switch is still loading: acting on it would skip an extra episode.
        assertFalse(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(postPlayDismissedForCurrentEpisode = true),
                hasInFlightNextEpisodeAutoPlay = false,
                hasRenderedFirstFrame = false
            )
        )
    }

    @Test
    fun `ended playback resets once the current stream has rendered a frame`() {
        assertTrue(
            shouldResetPostPlayStateAfterPlaybackEnded(
                state = PlayerUiState(postPlayDismissedForCurrentEpisode = true),
                hasInFlightNextEpisodeAutoPlay = false,
                hasRenderedFirstFrame = true
            )
        )
    }
}
