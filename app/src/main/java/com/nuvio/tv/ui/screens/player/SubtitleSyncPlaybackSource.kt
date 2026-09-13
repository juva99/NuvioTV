package com.nuvio.tv.ui.screens.player

/**
 * Shared identity and projection for the subtitle timeline currently visible to the player.
 *
 * A synchronized rewrite keeps the same addon track key as its remote source, so the rewrite
 * generation is part of the manual-calibration cache key. This prevents a pending download of the
 * old timeline from replacing cues from a newer synchronized document.
 */
internal object SubtitleSyncPlaybackSource {
    fun cacheKey(trackKey: String, synchronizedSourceUri: String?): String {
        val source = synchronizedSourceUri?.takeIf { it.isNotBlank() }
            ?: return trackKey
        return "$trackKey|synchronized=$source"
    }

    fun toUiCues(document: SrtDocument): List<SubtitleSyncCue> {
        return document.cues.map { cue ->
            SubtitleSyncCue(
                startTimeMs = cue.startMs,
                endTimeMs = cue.endMs,
                text = cue.text
            )
        }
    }
}
