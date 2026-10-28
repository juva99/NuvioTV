package com.nuvio.tv.ui.screens.player

internal object SeekPreviewPolicy {
    fun supportsSource(url: String, mimeType: String?, sdkInt: Int, isTorrent: Boolean): Boolean {
        if (sdkInt < 27 || isTorrent) return false
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return false
        // Be conservative: unknown formats and adaptive manifests are not progressive video files.
        return mimeType?.substringBefore(';')?.lowercase() in setOf(
            "video/mp4", "video/x-matroska", "video/webm", "video/avi", "video/x-msvideo",
            "video/mp2t", "video/mpeg", "video/quicktime"
        )
    }

    fun hasBufferReserve(positionMs: Long, bufferedPositionMs: Long, durationMs: Long): Boolean {
        if (positionMs < 0L || durationMs <= 0L || bufferedPositionMs <= positionMs) return false
        return bufferedPositionMs - positionMs >= 5_000L || bufferedPositionMs >= durationMs - 500L
    }
}
