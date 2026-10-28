package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.content.Intent
import android.graphics.Bitmap
import android.view.Choreographer
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.nuvio.tv.MainActivity
import com.nuvio.tv.ui.theme.NuvioTheme

/** Opt-in platform-codec smoke test. Supply a legal, range-capable HTTP video URL as a runner argument. */
@RunWith(AndroidJUnit4::class)
class SeekPreviewFrameExtractorDeviceTest {
    @Test
    fun playsUrlAndDisplaysPreviewInProductionPlayer(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("seekPreviewUrl")
        assumeTrue("Opt in with seekPreviewPlayer=true and a video URL", arguments.getString("seekPreviewPlayer") == "true" && url != null && Build.VERSION.SDK_INT >= 27)
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra("streamUrl", url)
            .putExtra("title", "Seek preview URL test")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var viewModel: PlayerViewModel
            scenario.onActivity { activity -> viewModel = ViewModelProvider(activity)[PlayerViewModel::class.java] }
            val settings = viewModel.controller.playerSettingsDataStore
            val previousOptIn = settings.playerSettings.first().seekPreviewsEnabled
            try {
                settings.setSeekPreviewsEnabled(true)
                scenario.onActivity { activity ->
                    // This is the real production player, hosted directly to avoid onboarding/addon
                    // setup for a public URL. It does not add a production debug/deep-link backdoor.
                    activity.setContent {
                        NuvioTheme {
                            PlayerScreen(viewModel = viewModel, onBackPress = { _, _, _, _, _ -> })
                        }
                    }
                }
                withTimeout(30_000L) {
                    combine(viewModel.uiState, viewModel.playbackTimeline) { state, timeline ->
                        state.isPlaying && !state.isBuffering && timeline.duration > 10_000L &&
                            SeekPreviewPolicy.hasBufferReserve(timeline.currentPosition, timeline.bufferedPosition, timeline.duration)
                    }.first { it }
                }
                withContext(Dispatchers.Main) {
                    // Pause to stabilize the screenshot, then exercise the same preview event as
                    // the D-pad handler. Remote focus/key-repeat behavior is a separate manual check.
                    viewModel.onEvent(PlayerEvent.OnPlayPause)
                    viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(PlayerScrubRates.STEP_SHORT_MS))
                    val target = viewModel.uiState.value.pendingPreviewSeekPosition!!
                    viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                    assertTrue("A single tap must commit immediately", viewModel.uiState.value.pendingPreviewSeekPosition == null)
                    assertTrue("The seek must not wait for the thumbnail", kotlin.math.abs(viewModel.exoPlayer!!.currentPosition - target) < 500L)
                }
                val preview = withTimeout(10_000L) { viewModel.seekPreviewState.first { it.frame != null } }
                assertNotNull(preview.positionMs)
                suspend fun capture(name: String) {
                    instrumentation.waitForIdleSync()
                    instrumentation.uiAutomation.waitForIdle(500L, 5_000L)
                    withContext(Dispatchers.Main) {
                        repeat(3) {
                            suspendCancellableCoroutine { continuation ->
                                Choreographer.getInstance().postFrameCallback {
                                    if (continuation.isActive) continuation.resume(Unit)
                                }
                            }
                        }
                    }
                    assertTrue("Capture requires a loaded preview", viewModel.seekPreviewState.value.frame != null)
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull("Could not capture the actual player UI", screenshot)
                    File(instrumentation.targetContext.cacheDir, name).outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
                capture("seek-preview-url-player.png")
                withContext(Dispatchers.Main) {
                    viewModel.hideControls()
                    viewModel.onEvent(PlayerEvent.OnSeekBy(-PlayerScrubRates.STEP_SHORT_MS))
                    assertTrue("Direct skips must not require a held key", viewModel.uiState.value.pendingPreviewSeekPosition == null)
                }
                withTimeout(10_000L) {
                    viewModel.seekPreviewState.first { it.frame != null && it.positionMs != preview.positionMs }
                }
                capture("seek-preview-url-seek-only.png")
                withTimeout(SeekPreviewCoordinator.POST_COMMIT_PREVIEW_MS + 1_000L) {
                    viewModel.seekPreviewState.first { it.positionMs == null }
                }
            } finally {
                withContext(Dispatchers.Main) { viewModel.stopAndRelease() }
                settings.setSeekPreviewsEnabled(previousOptIn)
            }
        }
    }

    @Test
    fun extractsScaledKeyframeThroughBoundedRangeReader() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("seekPreviewUrl")
        assumeTrue("Requires API 27+ and the seekPreviewUrl runner argument", Build.VERSION.SDK_INT >= 27 && url != null)
        val extractor = SeekPreviewFrameExtractor()
        try {
            val frame = extractor.extract(SeekPreviewSource(url!!, emptyMap(), 1), 10_000L)
            assertNotNull("Platform extraction returned no frame within the preview budgets", frame)
            assertTrue("Output must be scaled", frame!!.width in 1..320 && frame.height in 1..180)
        } finally {
            extractor.cancelRead()
        }
    }
}
