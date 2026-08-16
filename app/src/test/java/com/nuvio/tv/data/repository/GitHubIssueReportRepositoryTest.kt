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
        assertTrue(payload.body.contains("https://subtitles.example/file.srt"))
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

    @Test
    fun `fingerprint changes when timing evidence changes`() {
        val first = sampleInput(subtitleCueCount = 718)
        val second = first.copy(subtitleCueCount = 1_524)

        assertNotEquals(
            subtitleSyncFailureFingerprint(first),
            subtitleSyncFailureFingerprint(second)
        )
    }

    @Test
    fun `includes timing evidence without publishing subtitle text`() {
        val payload = buildSubtitleSyncIssuePayload(
            sampleInput(
                subtitleCueCount = 1_524,
                subtitleFirstCueMs = 0L,
                subtitleLastCueMs = 10_280_682L,
                referenceTracks = listOf(
                    SubtitleSyncReferenceInput(
                        name = "English",
                        language = "eng",
                        sourceMimeType = "application/pgs",
                        cueCount = 718,
                        firstCueMs = 122_623L,
                        lastCueMs = 5_559_846L
                    )
                )
            )
        )

        assertTrue(payload.body.contains("Subtitle cue count: `1524`"))
        assertTrue(payload.body.contains("Subtitle timeline: `0..10280682 ms`"))
        assertTrue(payload.body.contains("timeline=122623..5559846 ms"))
        assertFalse(payload.body.contains("שלום"))
    }

    @Test
    fun `redacts credentials from diagnostic failure text`() {
        val payload = buildSubtitleSyncIssuePayload(
            sampleInput(failureReason = "GET https://example.test/file.srt?credential=secret-value authorization=Bearer abc123")
        )

        assertTrue(payload.body.contains("https://example.test/file.srt"))
        assertFalse(payload.body.contains("secret-value"))
        assertFalse(payload.body.contains("abc123"))
    }

    private fun sampleInput(
        subtitleUrl: String = "https://subtitles.example/file.srt",
        failureReason: String = "low confidence",
        subtitleCueCount: Int? = null,
        subtitleFirstCueMs: Long? = null,
        subtitleLastCueMs: Long? = null,
        referenceTracks: List<SubtitleSyncReferenceInput> = listOf(
            SubtitleSyncReferenceInput(
                name = "English",
                language = "en",
                sourceMimeType = "application/pgs",
                cueCount = 42
            )
        )
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
        referenceTracks = referenceTracks,
        subtitleCueCount = subtitleCueCount,
        subtitleFirstCueMs = subtitleFirstCueMs,
        subtitleLastCueMs = subtitleLastCueMs
    )
}
