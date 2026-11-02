package dev.ahpclient.auth

import dev.ahpclient.http.OkHttpTransport
import dev.ahpclient.platform.Instant
import dev.ahpclient.platform.instantNow
import okhttp3.OkHttpClient

fun GitHubDeviceAuth(
    http: OkHttpClient,
    baseUrl: String = "https://github.com",
    clientId: String = DEV_TUNNELS_GITHUB_APP_CLIENT_ID,
    now: () -> Instant = ::instantNow,
): GitHubDeviceAuth = GitHubDeviceAuth(OkHttpTransport(http), baseUrl, clientId, now)
