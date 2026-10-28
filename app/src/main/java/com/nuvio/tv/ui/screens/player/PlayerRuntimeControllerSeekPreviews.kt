package com.nuvio.tv.ui.screens.player

import android.os.Build
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.observeSeekPreviewPlaybackHealth() {
    scope.launch {
        // Timeline updates also re-evaluate the real buffer reserve during a long key hold.
        combine(
            _uiState.map { Triple(it.isBuffering, it.error, it.playbackEnded) }.distinctUntilChanged(),
            playbackTimeline
        ) { _, _ -> Unit }.collect { refreshSeekPreview() }
    }
}

internal fun PlayerRuntimeController.invalidateSeekPreviews() {
    seekPreviewGeneration++
    seekPreviewCoordinator.update(null, null, false)
}

internal fun PlayerRuntimeController.refreshSeekPreview() {
    val state = _uiState.value
    val timeline = _playbackTimeline.value
    if (!seekPreviewsEnabled || isInBackground || isReleasingPlayer || timeline.isLive ||
        !SeekPreviewPolicy.supportsSource(currentStreamUrl, currentStreamMimeType, Build.VERSION.SDK_INT, isTorrentStream)
    ) {
        seekPreviewCoordinator.update(null, null, false)
        return
    }
    val player = _exoPlayer
    val usingMpv = isUsingMpvEngine()
    val position = currentPlaybackPositionMs() ?: 0L
    // The UI timeline contains the *preview* position and may inflate bufferedPosition to it.
    // Never use that display value to decide whether playback has enough actual buffered data.
    val buffered = if (usingMpv) {
        position + ((mpvView?.demuxerCacheDurationSec() ?: 0.0) * 1_000.0).toLong()
    } else {
        player?.bufferedPosition ?: 0L
    }
    val healthy = hasRenderedFirstFrame && !state.isBuffering && state.error == null && !state.playbackEnded &&
        (usingMpv || player?.isCurrentMediaItemSeekable == true) && player?.videoFormat?.drmInitData == null &&
        SeekPreviewPolicy.hasBufferReserve(position, buffered, timeline.duration)
    val headers = currentHeaders.toMutableMap().apply {
        if (keys.none { it.equals("User-Agent", true) }) put("User-Agent", PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
    }
    seekPreviewCoordinator.update(
        SeekPreviewSource(currentStreamUrl, headers, seekPreviewGeneration),
        pendingPreviewSeekPosition,
        healthy
    )
}
