package dev.ahpclient.tunnel

import dev.ahpclient.http.OkHttpTransport
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

fun DevTunnelsClient(
    http: OkHttpClient,
    serviceUrl: String = "https://global.rel.tunnels.api.visualstudio.com",
    userAgent: String = "ahp-android-client/0.1",
): DevTunnelsClient = DevTunnelsClient(OkHttpTransport(http), serviceUrl, userAgent)

internal fun DevTunnelsClient.Companion.clusterUrl(base: HttpUrl, clusterId: String): HttpUrl =
    if (base.host.startsWith("global.")) {
        base.newBuilder().host(clusterId + base.host.substring(base.host.indexOf('.'))).build()
    } else {
        base
    }
