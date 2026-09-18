package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleRequestHeadersTest {

    @Test
    fun `empty explicit headers fall back to the matching subtitle metadata`() {
        val url = "https://subtitles.example/track.vtt"
        val known = subtitle(
            url = url,
            headers = mapOf("Referer" to "https://subtitles.example/")
        )

        assertEquals(
            known.headers,
            resolveSubtitleRequestHeaders(
                url = url,
                customHeaders = emptyMap(),
                streamSubtitles = listOf(known),
                addonSubtitles = emptyList(),
                selectedSubtitle = null
            )
        )
    }

    @Test
    fun `nonempty explicit headers override subtitle metadata`() {
        val url = "https://subtitles.example/track.vtt"

        assertEquals(
            mapOf("X-Subtitle-Token" to "explicit"),
            resolveSubtitleRequestHeaders(
                url = url,
                customHeaders = mapOf("X-Subtitle-Token" to "explicit"),
                streamSubtitles = listOf(
                    subtitle(url, mapOf("X-Subtitle-Token" to "stored"))
                ),
                addonSubtitles = emptyList(),
                selectedSubtitle = null
            )
        )
    }

    @Test
    fun `only transient subtitle HTTP responses are retryable`() {
        listOf(408, 425, 429, 500, 502, 599).forEach { code ->
            assertEquals(true, isRetryableSubtitleHttpStatus(code))
        }
        listOf(400, 401, 403, 404, 410).forEach { code ->
            assertEquals(false, isRetryableSubtitleHttpStatus(code))
        }
    }

    private fun subtitle(url: String, headers: Map<String, String>): Subtitle =
        Subtitle(
            id = "subtitle-id",
            url = url,
            lang = "en",
            addonName = "Test",
            addonLogo = null,
            headers = headers
        )
}
