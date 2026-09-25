package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.ui.screens.player.SrtCue
import com.nuvio.tv.ui.screens.player.SrtDocument

internal fun retimeSubtitleDocument(
    document: SrtDocument,
    timeline: AutoSyncTimelineRetimeResult
): SrtDocument? {
    if (!timeline.confident || document.cues.isEmpty()) return null
    val corrected = timeline.cues.associateBy { it.originalStartTimeMs to it.originalEndTimeMs }
    if (corrected.isEmpty()) return null
    val cues = document.cues.map { cue ->
        val timing = corrected[cue.startMs to cue.endMs] ?: return null
        SrtCue(timing.startTimeMs, timing.endTimeMs, cue.text)
    }
    if (cues.any { it.endMs <= it.startMs }) return null
    return SrtDocument(cues.sortedBy(SrtCue::startMs))
}
