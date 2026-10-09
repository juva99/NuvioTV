package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import java.text.Bidi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerSubtitleRtlFixTest {
    @Test
    fun dialoguePunctuationStaysInTheRtlRunWithoutMovingCharacters() {
        val line = "- \u05e9\u05dc\u05d5\u05dd!"
        val fixed = PlayerSubtitleRtlFix.fixText(line).toString()

        assertEquals("\u202B$line\u202C", fixed)
        val bidi = Bidi(fixed, Bidi.DIRECTION_LEFT_TO_RIGHT)
        assertEquals(1, bidi.getLevelAt(fixed.indexOf('-')) % 2)
        assertEquals(1, bidi.getLevelAt(fixed.indexOf('!')) % 2)
    }

    @Test
    fun numbersQuotesAndBracketsRetainTheirLogicalOrder() {
        val line = "\"\u05e9\u05dc\u05d5\u05dd (12:30), 16,300 - OK?\""
        assertEquals("\u202B$line\u202C", PlayerSubtitleRtlFix.fixText(line).toString())
    }

    @Test
    fun embeddedLegacyDialogueIsRepairedBeforeEmbedding() {
        val line = ".\u05e9\u05dc\u05d5\u05dd-"
        val expected = "\u202B-\u05e9\u05dc\u05d5\u05dd.\u202C"
        assertEquals(expected, PlayerSubtitleRtlFix.fixText(line).toString())
        val bidi = Bidi(expected, Bidi.DIRECTION_LEFT_TO_RIGHT)
        assertEquals(1, bidi.getLevelAt(expected.indexOf('-')) % 2)
        assertEquals(1, bidi.getLevelAt(expected.indexOf('.')) % 2)
    }

    @Test
    fun logicalLeadingEllipsisIsNotMistakenForLegacyPunctuation() {
        val line = "...\u05e9\u05dc\u05d5\u05dd"
        assertEquals("\u202B$line\u202C", PlayerSubtitleRtlFix.fixText(line).toString())
    }

    @Test
    fun eachParagraphUsesItsOwnDirectionAndKeepsLineEndings() {
        val hebrew = "- \u05e9\u05dc\u05d5\u05dd."
        val arabic = "\u0645\u0631\u062d\u0628\u0627!"
        val text = "$hebrew\r\nHello!\n\r$arabic\u2028$hebrew\u2029"
        assertEquals(
            "\u202B$hebrew\u202C\r\nHello!\n\r\u202B$arabic\u202C\u2028\u202B$hebrew\u202C\u2029",
            PlayerSubtitleRtlFix.fixText(text).toString()
        )
    }

    @Test
    fun englishParagraphWithHebrewWordIsUnchanged() {
        val text = "Hello \u05e9\u05dc\u05d5\u05dd!"
        assertSame(text, PlayerSubtitleRtlFix.fixText(text))
    }

    @Test
    fun repeatedNormalizationDoesNotWrapAgain() {
        val fixed = PlayerSubtitleRtlFix.fixText("- \u05e9\u05dc\u05d5\u05dd!\nGoodbye")
        assertSame(fixed, PlayerSubtitleRtlFix.fixText(fixed))
    }

    @Test
    fun authoredIsolatesAndDirectionMarksArePreserved() {
        val text = "\u200F\u05e9\u05dc\u05d5\u05dd \u2066English (12)\u2069!"
        assertEquals("\u202B$text\u202C", PlayerSubtitleRtlFix.fixText(text).toString())
        val ltr = "\u200EEnglish \u05e9\u05dc\u05d5\u05dd!"
        assertSame(ltr, PlayerSubtitleRtlFix.fixText(ltr))
    }

    @Test
    fun isolatedEnglishNameDoesNotChangeHebrewParagraphDirection() {
        val text = "- \u2066John\u2069: \u05e9\u05dc\u05d5\u05dd!"
        assertEquals("\u202B$text\u202C", PlayerSubtitleRtlFix.fixText(text).toString())
    }

    @Test
    fun embeddedAndSidecarCuesUseTheSameTextAndKeepTiming() {
        val text = "- \u05e9\u05dc\u05d5\u05dd!"
        val cue = Cue.Builder().setText(text).build()
        val embedded = PlayerSubtitleRtlFix.fixCueText(cue)
        val sidecar = PlayerSubtitleRtlFix.fixTimedCues(
            listOf(CuesWithTiming(listOf(cue), 12_000_000L, 2_000_000L))
        )
        assertEquals(embedded.text.toString(), sidecar.single().cues.single().text.toString())
        assertEquals(12_000_000L, sidecar.single().startTimeUs)
        assertEquals(2_000_000L, sidecar.single().durationUs)
        assertSame(embedded, PlayerSubtitleRtlFix.fixCueText(embedded))
        assertSame(sidecar, PlayerSubtitleRtlFix.fixTimedCues(sidecar))
    }

    @Test
    fun neutralAndEmptyTextIsUnchanged() {
        for (text in listOf("", " \n\r", "12:30 - (42)", "\uD83D\uDE00")) {
            assertSame(text, PlayerSubtitleRtlFix.fixText(text))
        }
    }

    @Test
    fun legacyConventionAppliesAcrossTheTrackAndKeepsTiming() {
        val original = listOf(
            timedCue(".\u05e9\u05dc\u05d5\u05dd-", 12_000_000L),
            timedCue("...\u05e9\u05dc\u05d5\u05dd", 14_000_000L)
        )
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(original)
        assertEquals("\u202B-\u05e9\u05dc\u05d5\u05dd.\u202C", fixed[0].cues.single().text.toString())
        assertEquals("\u202B\u05e9\u05dc\u05d5\u05dd...\u202C", fixed[1].cues.single().text.toString())
        original.zip(fixed).forEach { (before, after) ->
            assertEquals(before.startTimeUs, after.startTimeUs)
            assertEquals(before.durationUs, after.durationUs)
        }
        assertSame(fixed, PlayerSubtitleRtlFix.fixTimedCues(fixed))
    }

    @Test
    fun logicalSentenceEvidencePreventsTrackWideLegacyRepair() {
        val logical = "\u05e9\u05dc\u05d5\u05dd."
        val leading = ".\u05e9\u05dc\u05d5\u05dd"
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(
            listOf(timedCue(logical, 0L), timedCue(leading, 2_000_000L))
        )
        assertEquals("\u202B$logical\u202C", fixed[0].cues.single().text.toString())
        assertEquals("\u202B$leading\u202C", fixed[1].cues.single().text.toString())
    }

    private fun timedCue(text: String, startTimeUs: Long) =
        CuesWithTiming(listOf(Cue.Builder().setText(text).build()), startTimeUs, 2_000_000L)
}
