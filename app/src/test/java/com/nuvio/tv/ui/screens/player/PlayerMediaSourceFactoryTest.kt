package com.nuvio.tv.ui.screens.player

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class PlayerMediaSourceFactoryTest {

    @Test
    fun `scheme-aware progressive factory routes local subtitle resources locally and network media upstream`() {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.contentResolver } returns mockk<ContentResolver>(relaxed = true)
        val upstream = RecordingDataSourceFactory()
        val routedFactory = PlayerMediaSourceFactory.createSchemeAwareProgressiveDataSourceFactory(
            context = context,
            progressiveFactory = upstream
        )

        val contentUri = mockk<Uri>(relaxed = true) {
            every { scheme } returns "content"
        }
        val localDataSource = routedFactory.createDataSource()
        assertThrows(IOException::class.java) {
            localDataSource.open(
                DataSpec.Builder()
                    .setUri(contentUri)
                    .build()
            )
        }
        localDataSource.close()
        assertEquals(0, upstream.openCount)

        val networkUri = mockk<Uri>(relaxed = true) {
            every { scheme } returns "https"
        }
        val networkDataSource = routedFactory.createDataSource()
        assertEquals(
            RecordingDataSourceFactory.OPEN_LENGTH,
            networkDataSource.open(
                DataSpec.Builder()
                    .setUri(networkUri)
                    .build()
            )
        )
        networkDataSource.close()

        assertEquals(1, upstream.openCount)
        assertEquals(networkUri, upstream.lastOpenedUri)
    }

    @Test
    fun `media segment 404 with an alternative prefers another HLS track`() {
        assertTrue(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MEDIA,
                alternativeTrackAvailable = true
            )
        )
    }

    @Test
    fun `manifest errors and missing alternatives do not trigger rendition fallback`() {
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MANIFEST,
                alternativeTrackAvailable = true
            )
        )
        assertFalse(
            shouldPreferAlternativeHlsTrack(
                responseCode = 404,
                dataType = C.DATA_TYPE_MEDIA,
                alternativeTrackAvailable = false
            )
        )
    }

    @Test
    fun `inferMimeType prefers response content type for manifest urls without extension`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl; charset=UTF-8")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType uses content disposition filename when content type is missing`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = null,
            responseHeaders = mapOf("Content-Disposition" to "attachment; filename=manifest.mpd")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType ignores generic playlist path without manifest evidence`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/api/playlist/stream",
            filename = null
        )

        assertNull(mimeType)
    }

    @Test
    fun `inferMimeType recognizes explicit format query values`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/playback?format=m3u8",
            filename = null
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `normalizeMimeType recognizes redirected matroska file responses`() {
        val mimeType = PlayerMediaSourceFactory.normalizeMimeType("video/x-matroska")

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType uses filename star content disposition for octet stream responses`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/extract?id=42",
            filename = null,
            responseHeaders = mapOf(
                "Content-Type" to "application/octet-stream",
                "Content-Disposition" to "attachment; filename*=UTF-8''episode-04.mkv"
            )
        )

        assertEquals(MimeTypes.VIDEO_MATROSKA, mimeType)
    }

    @Test
    fun `inferMimeType prefers URL extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/stream.m3u8",
            filename = null,
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_M3U8, mimeType)
    }

    @Test
    fun `inferMimeType prefers filename extension for adaptive formats even if headers specify different type`() {
        val mimeType = PlayerMediaSourceFactory.inferMimeType(
            url = "https://example.com/download?id=42",
            filename = "movie.mpd",
            responseHeaders = mapOf("Content-Type" to "video/mp4")
        )

        assertEquals(MimeTypes.APPLICATION_MPD, mimeType)
    }

    @Test
    fun `inferMimeType recognizes playlist endpoints with numeric or token ids as HLS`() {
        val urls = listOf(
            "https://example.com/playlist/759755?token=mock_token_123&expires=1788170323&h=1&lang=it",
            "https://example.com/playlist/123456",
            "https://example.com/playlist/a1b2c3d4e5f6?h=1",
            "https://example.com/hls/759755",
            "https://example.com/manifest/abc123456",
            "https://example.com/master/stream99",
            "https://example.com/live/stream.m3u",
            "https://example.com/playback?protocol=hls"
        )

        for (url in urls) {
            val mimeType = PlayerMediaSourceFactory.inferMimeType(
                url = url,
                filename = null
            )
            assertEquals("Expected HLS mimeType for $url", MimeTypes.APPLICATION_M3U8, mimeType)
        }
    }

    @Test
    fun `normalizePlaybackRequest converts URL userinfo to authorization header`() {
        val userInfo = "test-user:test-pass"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/movies/title.mkv?download=1",
            headers = mapOf("Range" to "bytes=0-1")
        )

        assertEquals("https://webdav.example.org/movies/title.mkv?download=1", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
        assertFalse(request.headers.containsKey("Range"))
    }

    @Test
    fun `normalizePlaybackRequest strips URL userinfo without replacing explicit authorization`() {
        val explicitAuth = "explicit:value".basicAuthHeader()
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + "test-user:test-pass" + "@webdav.example.org/movies/title.mkv",
            headers = mapOf("Authorization" to explicitAuth)
        )

        assertEquals("https://webdav.example.org/movies/title.mkv", request.url)
        assertEquals(explicitAuth, request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest preserves encoded path query and fragment when stripping userinfo`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo.replace("@", "%40") + "@webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            headers = emptyMap()
        )

        assertEquals(
            "https://webdav.example.org/files/Show%2FSeason%201/Episode%2001.mkv?name=a%2Fb#frag%2Fment",
            request.url
        )
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    @Test
    fun `normalizePlaybackRequest strips userinfo containing a literal at sign`() {
        val userInfo = "user:p@ss"
        val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
            url = "https://" + userInfo + "@webdav.example.org/files/title.mkv",
            headers = emptyMap()
        )

        assertEquals("https://webdav.example.org/files/title.mkv", request.url)
        assertEquals(userInfo.basicAuthHeader(), request.headers["Authorization"])
    }

    private fun String.basicAuthHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString(toByteArray(Charsets.UTF_8))

    private class RecordingDataSourceFactory : DataSource.Factory {
        var openCount: Int = 0
            private set
        var lastOpenedUri: Uri? = null
            private set

        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) = Unit

            override fun open(dataSpec: DataSpec): Long {
                openCount++
                lastOpenedUri = dataSpec.uri
                return OPEN_LENGTH
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

            override fun getUri(): Uri? = lastOpenedUri

            override fun close() = Unit
        }

        companion object {
            const val OPEN_LENGTH = 17L
        }
    }
}
