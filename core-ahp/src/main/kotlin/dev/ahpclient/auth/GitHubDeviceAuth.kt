package dev.ahpclient.auth

import dev.ahpclient.http.await
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * GitHub App "Visual Studio Tunnel Service": the Dev Tunnels service only accepts user tokens issued
 * for this app (a personal access token gets 401). Published as `prodGitHubAppClientId` in
 * `@microsoft/dev-tunnels-contracts`.
 */
const val DEV_TUNNELS_GITHUB_APP_CLIENT_ID = "Iv1.e7b89e013f801f03"

data class DeviceCode(
    val deviceCode: Secret,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

data class GitHubToken(
    val access: Secret,
    val refresh: Secret?,
    val expiresAt: Instant?,
)

sealed interface DeviceFlowResult {
    data class Success(val token: GitHubToken) : DeviceFlowResult

    data object Expired : DeviceFlowResult

    data object Denied : DeviceFlowResult

    data class Failed(val error: String, val description: String?) : DeviceFlowResult
}

/** What GitHub answered when asked to renew a sign-in. */
sealed interface RefreshResult {
    /** A new pair. The refresh token in it replaces the one that was sent: each can be used once. */
    data class Renewed(val token: GitHubToken) : RefreshResult

    /** GitHub will not renew this sign-in (it is too old, was used already, or was revoked): the user has to sign in again. */
    data class Rejected(val error: String) : RefreshResult
}

class GitHubAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** GitHub OAuth device flow: no client secret, so it is safe to run inside a mobile app. */
class GitHubDeviceAuth(
    private val http: OkHttpClient = OkHttpClient(),
    private val baseUrl: String = "https://github.com",
    private val clientId: String = DEV_TUNNELS_GITHUB_APP_CLIENT_ID,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun start(): DeviceCode {
        val body = postForm("/login/device/code", mapOf("client_id" to clientId))
        body.string("error")?.let { error ->
            throw GitHubAuthException("GitHub refused the device flow: $error ${body.string("error_description").orEmpty()}".trim())
        }
        return DeviceCode(
            deviceCode = Secret(body.requireString("device_code")),
            userCode = body.requireString("user_code"),
            verificationUri = body.requireString("verification_uri"),
            expiresInSeconds = body.int("expires_in") ?: DEFAULT_EXPIRES_SECONDS,
            intervalSeconds = body.int("interval") ?: DEFAULT_INTERVAL_SECONDS,
        )
    }

    /** Polls until the user approves, denies, or the code expires. Time is spent with [delay], so tests use virtual time. */
    suspend fun poll(code: DeviceCode): DeviceFlowResult {
        var intervalMs = code.intervalSeconds * 1000L
        var waitedMs = 0L
        val limitMs = code.expiresInSeconds * 1000L
        while (waitedMs < limitMs) {
            delay(intervalMs)
            waitedMs += intervalMs
            val body = try {
                postForm(
                    "/login/oauth/access_token",
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to code.deviceCode.reveal(),
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                )
            } catch (e: IOException) {
                continue // a network blip must not abort a flow the user is still completing
            }
            if (body.string("access_token") != null) return DeviceFlowResult.Success(body.toToken())
            when (val error = body.string("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs = body.int("interval")?.let { it * 1000L } ?: (intervalMs + SLOW_DOWN_STEP_MS)
                "expired_token" -> return DeviceFlowResult.Expired
                "access_denied" -> return DeviceFlowResult.Denied
                else -> return DeviceFlowResult.Failed(error ?: "unexpected response", body.string("error_description"))
            }
        }
        return DeviceFlowResult.Expired
    }

    /**
     * Trades a refresh token for a new token pair. GitHub asks for no client secret when the token came from the device flow, which
     * is how this app signs in. Throws [IOException] (or [GitHubAuthException] for a reply that is not JSON) when GitHub cannot be
     * reached or answers badly: that says nothing about the token, which must be kept and tried again later.
     */
    suspend fun refresh(refreshToken: Secret): RefreshResult {
        val body = postForm(
            "/login/oauth/access_token",
            mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refreshToken.reveal()),
        )
        if (body.string("access_token") != null) return RefreshResult.Renewed(body.toToken())
        return RefreshResult.Rejected(body.string("error") ?: "unexpected response")
    }

    private fun JsonObject.toToken(): GitHubToken = GitHubToken(
        access = Secret(requireString("access_token")),
        refresh = string("refresh_token")?.let(::Secret),
        expiresAt = int("expires_in")?.let { now().plusSeconds(it.toLong()) },
    )

    private suspend fun postForm(path: String, fields: Map<String, String>): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .post(form)
            .build()
        http.newCall(request).await().use { response ->
            val text = response.body.string()
            return try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: IllegalArgumentException) {
                throw GitHubAuthException("GitHub returned a non-JSON response (HTTP ${response.code})", e)
            }
        }
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

    private fun JsonObject.requireString(name: String): String =
        string(name) ?: throw GitHubAuthException("GitHub response is missing '$name'")

    private companion object {
        const val DEFAULT_EXPIRES_SECONDS = 900
        const val DEFAULT_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_STEP_MS = 5_000L
    }
}
