package com.nuvio.tv.ui.screens.player.autosync

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Optional Android AutoSync capability layered beside PlayerEngineController.
 * Other platforms do not need to implement it.
 */
internal interface AutoSyncPlayerController {
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}
