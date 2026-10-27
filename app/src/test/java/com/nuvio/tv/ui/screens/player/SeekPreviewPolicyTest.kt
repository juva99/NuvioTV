package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewPolicyTest {
    @Test
    fun `only supported progressive HTTP sources on API 27 or later are eligible`() {
        assertTrue(SeekPreviewPolicy.supportsSource("https://example.test/file", "video/x-matroska", 27, false))
        assertTrue(SeekPreviewPolicy.supportsSource("http://example.test/file", "video/mp4", 36, false))
        assertFalse(SeekPreviewPolicy.supportsSource("https://example.test/file", "video/mp4", 26, false))
        assertFalse(SeekPreviewPolicy.supportsSource("https://example.test/file", "video/mp4", 36, true))
        assertFalse(SeekPreviewPolicy.supportsSource("file:///file.mp4", "video/mp4", 36, false))
        listOf(null, "application/x-mpegURL", "application/dash+xml", "application/octet-stream").forEach {
            assertFalse(SeekPreviewPolicy.supportsSource("https://example.test/file", it, 36, false))
        }
    }

    @Test
    fun `buffer reserve is based on actual playback not the preview position`() {
        assertTrue(SeekPreviewPolicy.hasBufferReserve(10_000, 15_000, 100_000))
        assertFalse(SeekPreviewPolicy.hasBufferReserve(10_000, 14_999, 100_000))
        assertFalse(SeekPreviewPolicy.hasBufferReserve(20_000, 15_000, 100_000))
        assertFalse(SeekPreviewPolicy.hasBufferReserve(10_000, 15_000, 0))
        assertTrue(SeekPreviewPolicy.hasBufferReserve(98_000, 100_000, 100_000))
        assertFalse(SeekPreviewPolicy.hasBufferReserve(100_000, 100_000, 100_000))
    }

    @Test
    fun `positions are grouped into ten second buckets`() {
        assertEquals(0, SeekPreviewCoordinator.bucketMs(-1))
        assertEquals(0, SeekPreviewCoordinator.bucketMs(9_999))
        assertEquals(10_000, SeekPreviewCoordinator.bucketMs(19_999))
    }
}
