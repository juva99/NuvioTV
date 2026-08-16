package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GitHubDeviceCodeResponseDto(
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_uri") val verificationUri: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Long? = null,
    val interval: Long? = null
)

@JsonClass(generateAdapter = true)
data class GitHubOAuthTokenResponseDto(
    @Json(name = "access_token") val accessToken: String? = null,
    @Json(name = "refresh_token") val refreshToken: String? = null,
    @Json(name = "expires_in") val expiresInSeconds: Long? = null,
    @Json(name = "refresh_token_expires_in") val refreshTokenExpiresInSeconds: Long? = null,
    val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @Json(name = "error_description") val errorDescription: String? = null,
    @Json(name = "error_uri") val errorUri: String? = null,
    val interval: Long? = null
)
