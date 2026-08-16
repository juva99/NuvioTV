package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubIssueCreateRequestDto(
    val title: String,
    val body: String
)

@JsonClass(generateAdapter = true)
data class GitHubIssueResponseDto(
    @Json(name = "html_url") val htmlUrl: String? = null,
    val number: Int? = null
)
