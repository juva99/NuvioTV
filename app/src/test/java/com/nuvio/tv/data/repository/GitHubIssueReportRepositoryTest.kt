package com.nuvio.tv.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIssueReportRepositoryTest {
    @Test
    fun `builds a report with subtitle link and played file infohash`() {
        val payload = buildSubtitleSyncIssuePayload(
            sampleInput(
                subtitleUrl = "https://subtitles.example/file.srt?format=srt"
            )
        )

        assertTrue(payload.title.startsWith("[Subtitle Sync]"))
        assertTrue(payload.body.contains("https://subtitles.example/file.srt?format=srt"))
        assertTrue(payload.body.contains("abcdef1234567890"))
        assertTrue(payload.body.contains("episode.mkv"))
        assertTrue(payload.body.contains("Embedded subtitle references"))
    }

    @Test
    fun `removes credential-like subtitle query parameters from report links`() {
        val payload = buildSubtitleSyncIssuePayload(
            sampleInput(
                subtitleUrl = "https://subtitles.example/file.srt?token=do-not-publish&format=srt"
            )
        )

        assertTrue(payload.body.contains("https://subtitles.example/file.srt"))
        assertFalse(payload.body.contains("do-not-publish"))
        assertFalse(payload.body.contains("?token="))
    }

    @Test
    fun `fingerprint changes when the failure changes`() {
        val first = sampleInput(failureReason = "low confidence")
        val second = first.copy(failureReason = "invalid subtitle")

        assertNotEquals(
            subtitleSyncFailureFingerprint(first),
            subtitleSyncFailureFingerprint(second)
        )
    }

    private fun sampleInput(
        subtitleUrl: String = "https://subtitles.example/file.srt",
        failureReason: String = "low confidence"
    ) = SubtitleSyncFailureReportInput(
        title = "Episode",
        contentName = "Example Show",
        contentId = "tmdb:123",
        contentType = "series",
        videoId = "s1:e2",
        season = 1,
        episode = 2,
        episodeTitle = "The Example",
        releaseYear = "2026",
        subtitleId = "subtitle-1",
        subtitleUrl = subtitleUrl,
        subtitleLanguage = "en",
        subtitleAddonName = "Example Subs",
        playedFileInfoHash = "abcdef1234567890",
        playedFileIndex = 4,
        filename = "episode.mkv",
        streamName = "1080p",
        streamAddonName = "Example Streams",
        failureStage = "aligning subtitle timelines",
        failureReason = failureReason,
        referenceTracks = listOf(
            SubtitleSyncReferenceInput(
                name = "English",
                language = "en",
                sourceMimeType = "application/pgs",
                cueCount = 42
            )
        )
    )
}
