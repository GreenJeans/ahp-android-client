package dev.ahpclient.http

data class HttpResponseData(val code: Int, val body: String)

/**
 * Platform-neutral HTTP transport used by [dev.ahpclient.auth.GitHubDeviceAuth], [dev.ahpclient.tunnel.DevTunnelsClient],
 * and [dev.ahpclient.ai.CloudQuickReplyController].
 * Throws [dev.ahpclient.platform.IOException] on network failure.
 */
interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponseData
    suspend fun postForm(url: String, headers: Map<String, String>, fields: Map<String, String>): HttpResponseData
    suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResponseData =
        throw UnsupportedOperationException("postJson not implemented")
}

expect fun defaultHttpTransport(): HttpTransport
