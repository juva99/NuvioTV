package com.nuvio.tv.ui.screens.player.autosync

import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.SubtitleLanguageMatching

internal fun sameLanguageAutoSyncCandidates(
    selected: Subtitle,
    available: List<Subtitle>
): List<AutoSyncSubtitleCandidate> = available
    .asSequence()
    .filter { it.url.isNotBlank() && it.url != selected.url }
    .filter { SubtitleLanguageMatching.matchesLanguageCode(it.lang, selected.lang) }
    .distinctBy(Subtitle::url)
    .map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.lang,
            name = subtitle.addonName,
            headers = subtitle.headers.orEmpty()
        )
    }
    .toList()
