package com.nuvio.tv.data.repository

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.GitHubIssueReportingDataStore
import com.nuvio.tv.data.remote.api.GitHubIssueApi
import com.nuvio.tv.data.remote.dto.GitHubIssueCreateRequestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class SubtitleSyncReferenceInput(
    val name: String,
    val language: String?,
    val sourceMimeType: String,
    val cueCount: Int
)

data class SubtitleSyncFailureReportInput(
    val title: String?,
    val contentName: String?,
    val contentId: String?,
    val contentType: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val releaseYear: String?,
    val subtitleId: String?,
    val subtitleUrl: String,
    val subtitleLanguage: String?,
    val subtitleAddonName: String?,
    val playedFileInfoHash: String?,
    val playedFileIndex: Int?,
    val filename: String?,
    val streamName: String?,
    val streamAddonName: String?,
    val failureStage: String,
    val failureReason: String,
    val referenceTracks: List<SubtitleSyncReferenceInput>
)

@Singleton
class GitHubIssueReportRepository @Inject constructor(
    private val githubIssueApi: GitHubIssueApi,
    private val settingsStore: GitHubIssueReportingDataStore
) {
    private val submitMutex = Mutex()

    suspend fun isEnabled(): Boolean = settingsStore.settings.first().enabled

    suspend fun submit(input: SubtitleSyncFailureReportInput): Result<String> =
        submitMutex.withLock {
            try {
                val settings = settingsStore.settings.first()
                if (!settings.enabled) {
                    return@withLock Result.failure(
                        IllegalStateException("Automatic GitHub issue reporting is disabled")
                    )
                }

                val token = settingsStore.readToken()?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@withLock Result.failure(
                        IllegalStateException("A GitHub issue token is not configured")
                    )

                val nowMs = System.currentTimeMillis()
                val fingerprint = subtitleSyncFailureFingerprint(input)
                if (settingsStore.wasRecentlyReported(fingerprint, nowMs)) {
                    return@withLock settingsStore.lastIssueUrl()?.let { issueUrl ->
                        Result.success(issueUrl)
                    } ?: Result.failure(
                        IllegalStateException("Duplicate subtitle-sync issue was suppressed")
                    )
                }

                val response = githubIssueApi.createIssue(
                    owner = BuildConfig.GITHUB_ISSUE_OWNER,
                    repo = BuildConfig.GITHUB_ISSUE_REPO,
                    authorization = listOf("Bearer", token).joinToString(" "),
                    body = buildSubtitleSyncIssuePayload(input)
                )
                if (!response.isSuccessful) {
                    error("GitHub issue creation failed: HTTP ${response.code()}")
                }

                val responseBody = response.body()
                    ?: error("GitHub issue creation failed: empty response")
                val issueUrl = responseBody.htmlUrl?.trim()?.takeIf { it.isNotBlank() }
                    ?: responseBody.number
                        ?.takeIf { it > 0 }
                        ?.let { number ->
                            "https://github.com/${BuildConfig.GITHUB_ISSUE_OWNER}/" +
                                "${BuildConfig.GITHUB_ISSUE_REPO}/issues/$number"
                        }
                    ?: error("GitHub issue creation failed: missing issue URL")

                settingsStore.markReported(fingerprint, issueUrl, nowMs)
                Result.success(issueUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}

internal fun buildSubtitleSyncIssuePayload(
    input: SubtitleSyncFailureReportInput
): GitHubIssueCreateRequestDto {
    val mediaTitle = (input.contentName ?: input.title ?: input.filename)
        .cleanText(160)
        ?: "Unknown media"
    val safeSubtitleUrl = sanitizeSubtitleUrl(input.subtitleUrl)

    val body = buildString {
        appendLine("## Automatic subtitle-sync failure")
        appendLine()
        appendLine("- App version: `${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})`")
        appendLine("- Failure stage: `${input.failureStage.cleanText(120) ?: "unknown"}`")
        appendLine("- Failure reason: ${input.failureReason.cleanText(1_000) ?: "Unknown error"}")
        appendLine()
        appendLine("### Subtitle")
        appendLine("- Subtitle ID: `${input.subtitleId.cleanText(200) ?: "unknown"}`")
        appendLine("- Subtitle language: `${input.subtitleLanguage.cleanText(40) ?: "unknown"}`")
        appendLine("- Subtitle addon: `${input.subtitleAddonName.cleanText(160) ?: "unknown"}`")
        appendLine("- Subtitle used: ${safeSubtitleUrl?.let { "<$it>" } ?: "unavailable"}")
        appendLine()
        appendLine("### Played file")
        appendLine("- Content: `${mediaTitle}`")
        appendLine("- Content ID: `${input.contentId.cleanText(160) ?: "unknown"}`")
        appendLine("- Content type: `${input.contentType.cleanText(60) ?: "unknown"}`")
        appendLine("- Video ID: `${input.videoId.cleanText(160) ?: "unknown"}`")
        appendLine("- Season / episode: `${input.season ?: "?"} / ${input.episode ?: "?"}`")
        appendLine("- Episode title: `${input.episodeTitle.cleanText(160) ?: "unknown"}`")
        appendLine("- Release year: `${input.releaseYear.cleanText(20) ?: "unknown"}`")
        appendLine("- Filename: `${input.filename.cleanText(240) ?: "unknown"}`")
        appendLine("- File infohash: `${input.playedFileInfoHash.cleanText(160) ?: "unknown"}`")
        appendLine("- File index: `${input.playedFileIndex ?: "unknown"}`")
        appendLine("- Stream name: `${input.streamName.cleanText(200) ?: "unknown"}`")
        appendLine("- Stream addon: `${input.streamAddonName.cleanText(160) ?: "unknown"}`")
        appendLine()
        appendLine("### Embedded subtitle references")
        if (input.referenceTracks.isEmpty()) {
            appendLine("- None were available when synchronization failed.")
        } else {
            input.referenceTracks.take(MAX_REFERENCE_TRACKS).forEach { track ->
                appendLine(
                    "- `${track.name.cleanText(160) ?: "unnamed"}` " +
                        "(${track.language.cleanText(40) ?: "unknown"}, " +
                        "${track.sourceMimeType.cleanText(80) ?: "unknown"}, " +
                        "${track.cueCount.coerceAtLeast(0)} cues)"
                )
            }
        }
        appendLine()
        appendLine("_Subtitle URLs are stripped of query parameters when they look like credentials._")
    }

    return GitHubIssueCreateRequestDto(
        title = "[Subtitle Sync] $mediaTitle",
        body = body.take(MAX_ISSUE_BODY_LENGTH)
    )
}

internal fun subtitleSyncFailureFingerprint(input: SubtitleSyncFailureReportInput): String {
    val normalized = listOf(
        sanitizeSubtitleUrl(input.subtitleUrl).orEmpty(),
        input.playedFileInfoHash.cleanText(160).orEmpty(),
        input.filename.cleanText(240).orEmpty(),
        input.failureStage.cleanText(120).orEmpty(),
        input.failureReason.cleanText(240).orEmpty()
    ).joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private fun sanitizeSubtitleUrl(rawUrl: String): String? {
    val value = rawUrl.trim().takeIf { it.isNotBlank() } ?: return null
    val parsed = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return value.substringBefore('?').substringBefore('#').takeIf { it.isNotBlank() }
    }
    val scheme = parsed.scheme?.takeIf { it.isNotBlank() } ?: return null
    val host = parsed.host?.takeIf { it.isNotBlank() } ?: return null
    val authority = if (parsed.port in 1..65_535) "$host:${parsed.port}" else host
    val query = parsed.rawQuery?.takeUnless(::containsCredentialQueryParameter)
    return buildString {
        append(scheme)
        append("://")
        append(authority)
        append(parsed.rawPath.orEmpty())
        if (!query.isNullOrBlank()) {
            append('?')
            append(query)
        }
    }
}

private fun containsCredentialQueryParameter(query: String): Boolean =
    query.split('&').any { parameter ->
        parameter.substringBefore('=')
            .replace('-', '_')
            .lowercase()
            .let { key ->
                key.contains("token") ||
                    key.contains("auth") ||
                    key.contains("api_key") ||
                    key.contains("apikey") ||
                    key.contains("secret") ||
                    key.contains("password") ||
                    key == "key" ||
                    key.contains("signature") ||
                    key == "sig"
            }
    }

private fun String?.cleanText(maxLength: Int): String? =
    this?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotBlank() }
        ?.take(maxLength)

private const val MAX_REFERENCE_TRACKS = 12
private const val MAX_ISSUE_BODY_LENGTH = 60_000
