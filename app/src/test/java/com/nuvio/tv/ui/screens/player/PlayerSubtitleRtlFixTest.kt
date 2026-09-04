package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming
import java.text.Bidi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun realHebrewFixtureRestoresLegacyPunctuationAndDialogueDashes() {
        val original = fixture("real-heb-target.srt")
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(original)
        var sentenceLines = 0
        var dialogueLines = 0
        var hebrewLines = 0
        original.zip(fixed).forEachIndexed { index, (before, after) ->
            assertEquals(before.startTimeUs, after.startTimeUs)
            assertEquals(before.durationUs, after.durationUs)
            val beforeLines = before.cues.single().text.toString().lines()
            val afterLines = after.cues.single().text.toString().lines()
            assertEquals(beforeLines.size, afterLines.size)
            beforeLines.zip(afterLines).forEach { (source, normalized) ->
                val text = normalized.removePrefix("\u202B").removeSuffix("\u202C")
                assertEquals("Letters/numbers changed in cue $index",
                    source.filter(Char::isLetterOrDigit), text.filter(Char::isLetterOrDigit))
                if (source.none { it in '\u0590'..'\u05FF' }) return@forEach
                hebrewLines++
                if (source.firstOrNull() in listOf('.', '!', '?', ',', ';', ':')) {
                    sentenceLines++
                    assertTrue("Legacy sentence punctuation still leads cue $index",
                        text.firstOrNull() !in listOf('.', '!', '?', ',', ';', ':'))
                }
                if (source.endsWith('-')) {
                    dialogueLines++
                    assertTrue("Legacy dialogue dash still trails cue $index", text.startsWith('-'))
                }
            }
        }
        assertEquals(1203, hebrewLines)
        assertEquals(1057, sentenceLines)
        assertEquals(14, dialogueLines)
        assertSame(fixed, PlayerSubtitleRtlFix.fixTimedCues(fixed))
        println("Hebrew fixture: ${original.size} cues, $hebrewLines Hebrew lines, " +
            "$sentenceLines legacy sentence boundaries, $dialogueLines dialogue dashes")
    }

    @Test
    fun hebrewTimingOnlyFixtureIsUnchanged() {
        val cues = fixture("long-heb-target-timings.srt")
        assertTrue(cues.isNotEmpty())
        assertTrue(cues.none { entry ->
            entry.cues.any { cue -> cue.text?.any { it in '\u0590'..'\u05FF' } == true }
        })
        assertSame(cues, PlayerSubtitleRtlFix.fixTimedCues(cues))
        println("Timing-only fixture: ${cues.size} cues unchanged (no Hebrew text)")
    }

    private fun fixture(name: String): List<CuesWithTiming> {
        val resource = requireNotNull(javaClass.getResource("/subtitle-sync/$name"))
        return PlayerSubtitleCueParser.parseFromText(resource.readText(), name).map { cue ->
            CuesWithTiming(
                listOf(Cue.Builder().setText(cue.text).build()),
                cue.startTimeMs * 1_000,
                (cue.endTimeMs - cue.startTimeMs) * 1_000
            )
        }
    }
}
