package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.domain.model.Subtitle
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoSyncCandidateSelectionTest {
    private fun subtitle(url: String, language: String, headers: Map<String, String>? = null) =
        Subtitle(url, url, language, "addon", null, headers = headers)

    @Test
    fun aggressiveSearchOnlyIncludesOtherSameLanguageCandidates() {
        val selected = subtitle("https://example.org/selected", "eng")
        val alternatives = sameLanguageAutoSyncCandidates(
            selected,
            listOf(
                selected,
                subtitle("https://example.org/english", "en", mapOf("Authorization" to "required")),
                subtitle("https://example.org/french", "fr"),
                subtitle("https://example.org/english", "en")
            )
        )

        assertEquals(listOf("https://example.org/english"), alternatives.map { it.url })
        assertEquals(mapOf("Authorization" to "required"), alternatives.single().headers)
    }
}
