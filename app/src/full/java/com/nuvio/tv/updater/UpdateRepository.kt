package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton

internal class NoEligibleUpdateException(channel: UpdateChannel) :
    IllegalStateException("No compatible APK release found for ${channel.storedValue} channel")

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi
) {

    suspend fun getLatestUpdate(channel: UpdateChannel): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val releases = when (channel) {
                UpdateChannel.STABLE -> {
                    val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    listOf(response.body() ?: error("Empty GitHub release response"))
                }
                UpdateChannel.BETA -> {
                    val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                    if (!response.isSuccessful) {
                        error("GitHub API error: ${response.code()}")
                    }
                    response.body() ?: error("Empty GitHub release response")
                }
            }
            val releasesForSelection = if (
                channel == UpdateChannel.BETA &&
                BuildConfig.UPDATE_PRERELEASE_PREFIX.isNotBlank()
            ) {
                listOfNotNull(
                    selectLatestPrerelease(
                        releases,
                        BuildConfig.UPDATE_PRERELEASE_PREFIX
                    )
                )
            } else {
                releases
            }
            val releaseWithAsset = ReleaseSelector
                .eligibleReleases(releasesForSelection, channel)
                .firstNotNullOfOrNull { release ->
                    AbiSelector.chooseBestApkAsset(release.assets)?.let { asset ->
                        release to asset
                    }
                }
                ?: throw NoEligibleUpdateException(channel)
            val (dto, asset) = releaseWithAsset

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }
}

private const val SUBTITLE_SYNC_CHANNEL = "subtitle-sync"
private val subtitleSyncTagPattern =
    Regex("^v?\\d+\\.\\d+\\.\\d+-beta-subtitle-sync\\.\\d+$")

internal fun selectLatestPrerelease(
    releases: List<GitHubReleaseDto>,
    prefix: String
): GitHubReleaseDto? {
    val candidates = releases.asSequence()
        .filter { !it.draft && it.prerelease }

    if (prefix.contains(SUBTITLE_SYNC_CHANNEL, ignoreCase = true)) {
        return candidates
            .filter { subtitleSyncTagPattern.matches(it.tagName.orEmpty()) }
            .reduceOrNull { current, candidate ->
                if (VersionUtils.isRemoteNewer(candidate.tagName, current.tagName)) {
                    candidate
                } else {
                    current
                }
            }
    }

    return candidates
        .mapNotNull { release ->
            val tag = release.tagName.orEmpty()
            val number = tag.removePrefix(prefix).toIntOrNull()
            if (tag.startsWith(prefix) && number != null) release to number else null
        }
        .maxByOrNull { it.second }
        ?.first
}
