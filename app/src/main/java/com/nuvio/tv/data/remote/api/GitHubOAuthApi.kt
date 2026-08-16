package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.GitHubDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.GitHubOAuthTokenResponseDto
import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.Headers
import retrofit2.http.POST

interface GitHubOAuthApi {
    @Headers("Accept: application/json")
    @FormUrlEncoded
    @POST("login/device/code")
    suspend fun requestDeviceCode(
        @Field("client_id") clientId: String
    ): Response<GitHubDeviceCodeResponseDto>

    @Headers("Accept: application/json")
    @FormUrlEncoded
    @POST("login/oauth/access_token")
    suspend fun pollDeviceToken(
        @Field("client_id") clientId: String,
        @Field("device_code") deviceCode: String,
        @Field("grant_type") grantType: String,
        @Field("repository_id") repositoryId: String?
    ): Response<GitHubOAuthTokenResponseDto>

    @Headers("Accept: application/json")
    @FormUrlEncoded
    @POST("login/oauth/access_token")
    suspend fun refreshDeviceToken(
        @Field("client_id") clientId: String,
        @Field("grant_type") grantType: String,
        @Field("refresh_token") refreshToken: String
    ): Response<GitHubOAuthTokenResponseDto>
}
