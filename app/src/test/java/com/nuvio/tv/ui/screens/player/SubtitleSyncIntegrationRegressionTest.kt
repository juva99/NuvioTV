package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.collect.ImmutableList
import com.nuvio.tv.data.local.PlayerSettings
import io.mockk.every
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSyncIntegrationRegressionTest {

    @Test
    fun `reload synchronized subtitles cancels the existing sidecar before Exo replacement`() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val player = mockk<ExoPlayer>(relaxed = true)
        val mediaSourceFactory = mockk<PlayerMediaSourceFactory>(relaxed = true)
        val mediaSource = mockk<MediaSource>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val sidecarJob = Job()
        val subtitle = subtitle()
        val streamUrl = "https://video.example/movie.mkv"
        val override = synchronizedOverride(
            subtitle,
            streamUrl,
            "content://example/synced-reload.srt"
        )
        val state = MutableStateFlow(PlayerUiState(selectedAddonSubtitle = subtitle))

        every { controller._exoPlayer } returns player
        every { controller._uiState } returns state
        every { controller.synchronizedSubtitleOverride } returns override
        every { controller.sidecarSubtitleJob } returns sidecarJob
        every { controller.activeSidecarSubtitleKey } returns addonKey(subtitle)
        every { controller.exoSubtitleViewRef } returns null
        every { controller.mediaSourceFactory } returns mediaSourceFactory
        every { controller.context } returns context
        every { controller.currentStreamUrl } returns streamUrl
        every { controller.contentId } returns "movie"
        every { controller.contentType } returns "movie"
        every { controller.currentVideoId } returns "movie-1"
        every { controller.currentSeason } returns null
        every { controller.currentEpisode } returns null
        every { controller.currentInfoHash } returns null
        every { controller.currentFileIdx } returns null
        every { controller.currentHeaders } returns emptyMap()
        every { controller.currentFilename } returns "movie.mkv"
        every { controller.currentStreamResponseHeaders } returns emptyMap()
        every { controller.currentStreamMimeType } returns MimeTypes.VIDEO_MP4
        every { player.currentTracks } returns Tracks(ImmutableList.of<Tracks.Group>())
        every { player.currentPosition } returns 12_345L
        every { player.playWhenReady } returns true
        every { player.playbackParameters } returns PlaybackParameters(1.25f)
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        every {
            mediaSourceFactory.createMediaSource(
                context = any(),
                url = any(),
                headers = any(),
                subtitleConfigurations = any(),
                filename = any(),
                responseHeaders = any(),
                mimeTypeOverride = any(),
                audioDelayUsProvider = any(),
                mediaMetadata = any()
            )
        } returns mediaSource

        controller.reloadAddonSubtitlesForSync(subtitle)

        assertTrue(sidecarJob.isCancelled)
        verify(exactly = 1) { controller.sidecarSubtitleJob = null }
        verify(exactly = 1) { controller.activeSidecarSubtitleKey = null }
        verify(exactly = 1) { player.setMediaSource(mediaSource, 12_345L) }
    }

    @Test
    fun `controller override helper only matches the exact addon key`() {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val selected = subtitle()
        val streamUrl = "https://video.example/movie.mkv"
        val override = synchronizedOverride(selected, streamUrl, "content://example/synced-1.srt")
        every { controller.currentStreamUrl } returns streamUrl
        every { controller.contentId } returns "movie"
        every { controller.contentType } returns "movie"
        every { controller.currentVideoId } returns "movie-1"
        every { controller.currentSeason } returns null
        every { controller.currentEpisode } returns null
        every { controller.currentInfoHash } returns null
        every { controller.currentFileIdx } returns null
        every { controller.currentFilename } returns "movie.mkv"
        every { controller.synchronizedSubtitleOverride } returns override

        assertSame(override, controller.synchronizedSubtitleOverrideFor(selected))
        assertNull(controller.synchronizedSubtitleOverrideFor(selected.copy(id = "different-id")))
    }

    @Test
    fun `show timing dialog exposes synchronized cues without downloading original`() = runTest {
        val testScope = this
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val selected = subtitle()
        val streamUrl = "https://video.example/movie.mkv"
        val synchronizedUri = mockUri("content://example/synced-7.srt")
        val override = synchronizedOverride(selected, streamUrl, synchronizedUri.toString())
        val secondUri = "content://example/synced-8.srt"
        val secondOverride = synchronizedOverride(
            selected,
            streamUrl,
            secondUri,
            document = SrtDocument(
                cues = listOf(
                    SrtCue(startMs = 4_200L, endMs = 5_100L, text = "replacement")
                )
            )
        )
        val state = MutableStateFlow(PlayerUiState(selectedAddonSubtitle = selected))
        val context = mockk<Context>(relaxed = true)

        every { controller._uiState } returns state
        every { controller.scope } returns testScope
        every { controller.context } returns context
        every { controller.currentStreamUrl } returns streamUrl
        every { controller.contentId } returns "movie"
        every { controller.contentType } returns "movie"
        every { controller.currentVideoId } returns "movie-1"
        every { controller.currentSeason } returns null
        every { controller.currentEpisode } returns null
        every { controller.currentInfoHash } returns null
        every { controller.currentFileIdx } returns null
        every { controller.currentFilename } returns "movie.mkv"
        every { controller.synchronizedSubtitleOverride } returns override
        every { controller.exoSubtitleViewRef } returns null

        mockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerSubtitleTimingKt")
        try {
            coEvery {
                controller.downloadSubtitleBody(any(), any(), any())
            } returns "must not be downloaded"

            controller.showSubtitleTimingDialog()
            advanceUntilIdle()

            assertTrue(state.value.showSubtitleTimingDialog)
            assertEquals(
                listOf(
                    SubtitleSyncCue(1_240L, 2_860L, "first"),
                    SubtitleSyncCue(9_500L, 10_100L, "second")
                ),
                state.value.subtitleAutoSyncCues
            )
            assertNull(state.value.subtitleAutoSyncError)
            assertFalse(state.value.subtitleAutoSyncLoading)
            assertEquals(
                "${addonKey(selected)}|synchronized=$synchronizedUri",
                state.value.subtitleAutoSyncLoadedTrackKey
            )

            every { controller.synchronizedSubtitleOverride } returns secondOverride
            controller.showSubtitleTimingDialog()
            advanceUntilIdle()

            assertEquals(
                listOf(SubtitleSyncCue(4_200L, 5_100L, "replacement")),
                state.value.subtitleAutoSyncCues
            )
            assertEquals(
                "${addonKey(selected)}|synchronized=$secondUri",
                state.value.subtitleAutoSyncLoadedTrackKey
            )
        } finally {
            unmockkStatic("com.nuvio.tv.ui.screens.player.PlayerRuntimeControllerSubtitleTimingKt")
        }
    }

    @Test
    fun `same stream startup reattaches the synchronized addon URI`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val subtitle = subtitle()
        val streamUrl = "https://video.example/same-stream.mkv"
        val override = synchronizedOverride(subtitle, streamUrl, "content://example/startup.srt")
        val state = MutableStateFlow(PlayerUiState(selectedAddonSubtitle = subtitle))

        every { controller._uiState } returns state
        every { controller.currentStreamUrl } returns streamUrl
        every { controller.contentId } returns "movie"
        every { controller.contentType } returns "movie"
        every { controller.currentVideoId } returns "movie-1"
        every { controller.currentSeason } returns null
        every { controller.currentEpisode } returns null
        every { controller.currentInfoHash } returns null
        every { controller.currentFileIdx } returns null
        every { controller.currentFilename } returns "movie.mkv"
        every { controller.synchronizedSubtitleOverride } returns override
        every { controller.exoSubtitleViewRef } returns null

        val startup = controller.prepareStreamStartSubtitles(mockk<PlayerSettings>(relaxed = true))
        val configurations = controller.buildStartupSubtitleConfigurations(startup)

        assertEquals(listOf(subtitle), startup.attachedSubtitles)
        assertEquals(1, configurations.size)
        assertSame(override.uri, configurations.single().uri)
        assertEquals(
            controller.buildAddonSubtitleTrackId(subtitle),
            configurations.single().id
        )
        verify(exactly = 1) { controller.autoSubtitleSelected = true }
        verify(exactly = 1) {
            controller.pendingAddonSubtitleLanguage =
                PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        }
        verify(exactly = 1) {
            controller.pendingAddonSubtitleTrackId =
                controller.buildAddonSubtitleTrackId(subtitle)
        }
    }

    @Test
    fun `startup rejects synchronized addon from a different stream`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val subtitle = subtitle()
        val currentStreamUrl = "https://video.example/new-stream.mkv"
        val override = synchronizedOverride(
            subtitle,
            "https://video.example/old-stream.mkv",
            "content://example/stale.srt"
        )
        val state = MutableStateFlow(PlayerUiState(selectedAddonSubtitle = subtitle))

        every { controller._uiState } returns state
        every { controller.currentStreamUrl } returns currentStreamUrl
        every { controller.synchronizedSubtitleOverride } returns override
        every { controller.exoSubtitleViewRef } returns null

        val startup = controller.prepareStreamStartSubtitles(mockk<PlayerSettings>(relaxed = true))

        assertTrue(startup.attachedSubtitles.isEmpty())
        assertNull(controller.synchronizedSubtitleOverrideFor(subtitle))
        verify(exactly = 0) { controller.autoSubtitleSelected = true }
    }

    @Test
    fun `startup does not restore synchronized addon after subtitles were turned off`() = runTest {
        val controller = mockk<PlayerRuntimeController>(relaxed = true)
        val subtitle = subtitle()
        val streamUrl = "https://video.example/disabled-stream.mkv"
        val override = synchronizedOverride(subtitle, streamUrl, "content://example/disabled.srt")
        val state = MutableStateFlow(PlayerUiState())

        every { controller._uiState } returns state
        every { controller.currentStreamUrl } returns streamUrl
        every { controller.synchronizedSubtitleOverride } returns override
        every { controller.exoSubtitleViewRef } returns null

        val startup = controller.prepareStreamStartSubtitles(mockk<PlayerSettings>(relaxed = true))

        assertTrue(startup.attachedSubtitles.isEmpty())
        assertNull(state.value.selectedAddonSubtitle)
        verify(exactly = 0) { controller.autoSubtitleSelected = true }
    }

    private fun subtitle(): com.nuvio.tv.domain.model.Subtitle =
        com.nuvio.tv.domain.model.Subtitle(
            id = "subtitle-id",
            url = "https://subtitle.example/original.srt",
            lang = "el",
            addonName = "Test Addon",
            addonLogo = null,
            headers = mapOf("X-Subtitle-Test" to "1")
        )

    private fun synchronizedOverride(
        subtitle: com.nuvio.tv.domain.model.Subtitle,
        streamUrl: String,
        uri: String,
        document: SrtDocument = rewrittenDocument()
    ): SynchronizedSubtitleOverride {
        return SynchronizedSubtitleOverride(
            subtitleKey = addonKey(subtitle),
            streamUrl = streamUrl,
            contentIdentity = "movie|movie|movie-1|||movie.mkv||",
            uri = mockUri(uri),
            document = document
        )
    }

    private fun mockUri(value: String): Uri {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.toString() } returns value
        return uri
    }

    private fun addonKey(subtitle: com.nuvio.tv.domain.model.Subtitle): String {
        return "${subtitle.id}|${subtitle.url}"
    }

    private fun rewrittenDocument(): SrtDocument {
        return SrtDocument(
            cues = listOf(
                SrtCue(startMs = 1_240L, endMs = 2_860L, text = "first"),
                SrtCue(startMs = 9_500L, endMs = 10_100L, text = "second")
            )
        )
    }
}
