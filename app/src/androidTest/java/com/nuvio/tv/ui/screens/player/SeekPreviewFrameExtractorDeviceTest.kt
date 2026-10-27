package com.nuvio.tv.ui.screens.player

import android.os.Build
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in platform-codec smoke test. Supply a legal, range-capable HTTP video URL as a runner argument. */
@RunWith(AndroidJUnit4::class)
class SeekPreviewFrameExtractorDeviceTest {
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
