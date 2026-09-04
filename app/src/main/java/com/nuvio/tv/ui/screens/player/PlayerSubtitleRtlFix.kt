@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.extractor.text.CuesWithTiming

/**
 * Gives RTL subtitle paragraphs an explicit direction, repairing legacy Hebrew visual punctuation
 * only when the source provides evidence of that convention.
 * Both embedded and sidecar cues use the same normalization; styled text retains its spans.
 */
internal object PlayerSubtitleRtlFix {

    fun fixCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        val fixed = fixText(text)
        return if (fixed === text) cue else cue.buildUpon().setText(fixed).build()
    }

    internal fun fixText(
        text: CharSequence,
        legacyHebrewPunctuation: Boolean = usesLegacyHebrewPunctuation(sequenceOf(text))
    ): CharSequence {
        var builder: Appendable? = null
        var start = 0
        for (end in 0..text.length) {
            if (end != text.length && !isParagraphSeparator(text[end])) continue
            val line = text.subSequence(start, end)
            val needsEmbedding = isRtlParagraph(line) &&
                !(line.firstOrNull() == '\u202B' && line.lastOrNull() == '\u202C')
            if (needsEmbedding && builder == null) {
                builder = if (text is Spanned) SpannableStringBuilder() else StringBuilder(text.length + 8)
                builder.append(text.subSequence(0, start))
            }
            builder?.let { out ->
                // An embedding keeps boundary punctuation with the RTL run even in an LTR
                // container. Keep logical text and authored bidi controls intact.
                if (needsEmbedding) out.append('\u202B')
                out.append(
                    if (needsEmbedding && legacyHebrewPunctuation) repairLegacyHebrewLine(line)
                    else line
                )
                if (needsEmbedding) out.append('\u202C')
                if (end < text.length) out.append(text[end])
            }
            start = end + 1
        }
        return when (val result = builder) {
            null -> text
            is SpannableStringBuilder -> result
            else -> result.toString()
        }
    }

    /**
     * Applies [fixCueText] once at parse/load time, not on the sidecar render ticker.
     * Returns the original list when no cues change.
     */
    fun fixTimedCues(cues: List<CuesWithTiming>): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        val legacyHebrewPunctuation = usesLegacyHebrewPunctuation(
            cues.asSequence().flatMap { it.cues.asSequence() }.mapNotNull { it.text }
        )
        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val text = original.text
                val fixedText = text?.let { fixText(it, legacyHebrewPunctuation) }
                val fixed = if (fixedText != null && fixedText !== text) {
                    original.buildUpon().setText(fixedText).build()
                } else {
                    original
                }
                if (fixed !== original) {
                    if (modified == null) {
                        modified = ArrayList(entryCues.size)
                        for (j in 0 until i) {
                            modified.add(entryCues[j])
                        }
                    }
                    modified.add(fixed)
                } else {
                    modified?.add(original)
                }
            }
            if (modified != null) {
                anyChanged = true
                out.add(copyTimedCues(entry, modified))
            } else {
                out.add(entry)
            }
        }
        return if (anyChanged) out else cues
    }

    private fun copyTimedCues(entry: CuesWithTiming, cues: List<Cue>): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> 5_000_000L
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    private fun isParagraphSeparator(char: Char): Boolean =
        char == '\n' || char == '\r' || char == '\u2028' || char == '\u2029'

    private fun usesLegacyHebrewPunctuation(texts: Sequence<CharSequence>): Boolean {
        var legacyLines = 0
        var logicalLines = 0
        for (text in texts) {
            for (line in text.split('\n', '\r', '\u2028', '\u2029')) {
                if (!isUnmarkedHebrewLine(line)) continue
                val core = line.trim().trim('"', '\'')
                if (core.lastOrNull() in SENTENCE_PUNCTUATION) logicalLines++
                // Leading ellipses can be intentional in logical subtitles; do not use them
                // as evidence. Once a file's legacy convention is known, repair them as well.
                if (core.firstOrNull() in SENTENCE_PUNCTUATION && !core.startsWith("..")) legacyLines++
            }
        }
        return legacyLines > 0 && legacyLines >= logicalLines * 4
    }

    private fun isUnmarkedHebrewLine(line: CharSequence): Boolean =
        line.any { it in '\u0590'..'\u05FF' || it in '\uFB1D'..'\uFB4F' } &&
            line.none {
                it == '\u061C' || it == '\u200E' || it == '\u200F' ||
                    it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069'
            } && isRtlParagraph(line)

    private fun repairLegacyHebrewLine(line: CharSequence): CharSequence {
        if (!isUnmarkedHebrewLine(line)) return line
        val trimmed = line.trim()
        if (trimmed.firstOrNull() == '-' || trimmed.lastOrNull() in SENTENCE_PUNCTUATION) return line
        var start = 0
        while (start < line.length && isBoundaryPunctuation(line[start])) start++
        var end = line.length
        while (end > start && isBoundaryPunctuation(line[end - 1])) end--
        if (start == 0 && end == line.length) return line

        val out: Appendable = if (line is Spanned) SpannableStringBuilder() else StringBuilder(line.length)
        appendReversedBoundary(out, line, end, line.length)
        out.append(line.subSequence(start, end))
        appendReversedBoundary(out, line, 0, start)
        return if (out is SpannableStringBuilder) out else out.toString()
    }

    private fun isBoundaryPunctuation(char: Char): Boolean =
        char.isWhitespace() || char in ".,?!:;-'\u2013\u2014\u2026\"()[]{}"

    private fun appendReversedBoundary(out: Appendable, line: CharSequence, start: Int, end: Int) {
        for (index in end - 1 downTo start) {
            val mirrored = when (line[index]) {
                '(' -> ')'
                ')' -> '('
                '[' -> ']'
                ']' -> '['
                '{' -> '}'
                '}' -> '{'
                else -> line[index]
            }
            val original = line.subSequence(index, index + 1)
            out.append(
                when {
                    mirrored == line[index] -> original
                    original is Spanned -> SpannableStringBuilder(original).replace(0, 1, mirrored.toString())
                    else -> mirrored.toString()
                }
            )
        }
    }

    private val SENTENCE_PUNCTUATION = setOf('.', ',', '!', '?', ':', ';')

    private fun isRtlParagraph(text: CharSequence): Boolean {
        var index = 0
        var isolateDepth = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            val direction = Character.getDirectionality(codePoint)
            when (direction) {
                Character.DIRECTIONALITY_LEFT_TO_RIGHT_ISOLATE,
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ISOLATE,
                Character.DIRECTIONALITY_FIRST_STRONG_ISOLATE -> isolateDepth++
                Character.DIRECTIONALITY_POP_DIRECTIONAL_ISOLATE ->
                    isolateDepth = (isolateDepth - 1).coerceAtLeast(0)
                else -> if (isolateDepth == 0) {
                    // Isolated names/numbers do not determine the surrounding paragraph direction.
                    when (direction) {
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
                        Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE -> return true
                        Character.DIRECTIONALITY_LEFT_TO_RIGHT,
                        Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
                        Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE -> return false
                    }
                }
            }
            index += Character.charCount(codePoint)
        }
        return false
    }
}
