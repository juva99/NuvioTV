package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.GitHubIssueCreateRequestDto
import com.nuvio.tv.data.remote.dto.GitHubIssueResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

interface GitHubIssueApi {
    @Headers(
        "Accept: application/vnd.github+json",
        "X-GitHub-Api-Version: 2022-11-28"
    )
    @POST("repos/{owner}/{repo}/issues")
    suspend fun createIssue(
        @Path("owner") owner: String,
        @Path("repo") repo: String,
        @Header("Authorization") authorization: String,
        @Body body: GitHubIssueCreateRequestDto
    ): Response<GitHubIssueResponseDto>
}
