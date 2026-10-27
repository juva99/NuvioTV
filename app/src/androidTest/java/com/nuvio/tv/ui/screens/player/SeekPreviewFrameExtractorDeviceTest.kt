package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.media.MediaMetadataRetriever
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
import okhttp3.OkHttpClient
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
    fun playsUrlAndDisplaysPreviewInProductionPlayer() = runBlocking {
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
                    viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(8_000L))
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
                    viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(4_000L))
                }
                withTimeout(10_000L) {
                    viewModel.seekPreviewState.first { it.frame != null && it.positionMs != preview.positionMs }
                }
                withContext(Dispatchers.Main) {
                    // Refresh the normal seek-overlay timeout after decoding the second frame.
                    viewModel.onEvent(PlayerEvent.OnPreviewSeekBy(0L))
                }
                capture("seek-preview-url-seek-only.png")
                withContext(Dispatchers.Main) {
                    viewModel.onEvent(PlayerEvent.OnCommitPreviewSeek)
                    assertTrue("Commit must clear the pending preview", viewModel.uiState.value.pendingPreviewSeekPosition == null)
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
        if (InstrumentationRegistry.getArguments().getString("seekPreviewLocalBaseline") == "true") {
            // Optional diagnostic: establish whether this device can decode the same clip from disk.
            val file = File.createTempFile("seek-preview-baseline-", ".mp4", InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
            val baseline = MediaMetadataRetriever()
            try {
                OkHttpClient().newCall(okhttp3.Request.Builder().url(url!!).build()).execute().use { response ->
                    assertTrue("Baseline fixture must be a small successful response", response.isSuccessful && response.body.contentLength() in 1..32L * 1024 * 1024)
                    response.body.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                baseline.setDataSource(file.absolutePath)
                assertNotNull(
                    "Local-file codec baseline failed (duration=${baseline.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)})",
                    baseline.getScaledFrameAtTime(10_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
                )
            } finally {
                baseline.release()
                file.delete()
            }
        }
        // Surface a server/network failure directly rather than only the native retriever's status code.
        SeekPreviewRangeReader(OkHttpClient(), url!!, emptyMap()).use { reader ->
            assertTrue("Test video must support bounded byte-range reads", reader.size() > 0L)
        }
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
