@file:OptIn(kotlinx.cinterop.BetaInteropApi::class)

package dev.ahpclient.http

import dev.ahpclient.platform.IOException
import dev.ahpclient.platform.urlEncodeUtf8
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue

class IosHttpTransport(
    private val session: NSURLSession = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration(),
    ),
) : HttpTransport {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
        val nsUrl = NSURL.URLWithString(url) ?: throw IOException("Invalid URL: $url")
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("GET")
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
        }
        return execute(request)
    }

    override suspend fun postForm(url: String, headers: Map<String, String>, fields: Map<String, String>): HttpResponseData {
        val nsUrl = NSURL.URLWithString(url) ?: throw IOException("Invalid URL: $url")
        val formBody = fields.entries.joinToString("&") { (k, v) ->
            "${urlEncodeUtf8(k)}=${urlEncodeUtf8(v)}"
        }
        val bodyData: NSData? = NSString.create(string = formBody).dataUsingEncoding(NSUTF8StringEncoding)
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setValue("application/x-www-form-urlencoded", forHTTPHeaderField = "Content-Type")
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
            setHTTPBody(bodyData)
        }
        return execute(request)
    }

    override suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResponseData {
        val nsUrl = NSURL.URLWithString(url) ?: throw IOException("Invalid URL: $url")
        val bodyData: NSData? = NSString.create(string = jsonBody).dataUsingEncoding(NSUTF8StringEncoding)
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
            headers.forEach { (k, v) -> setValue(v, forHTTPHeaderField = k) }
            setHTTPBody(bodyData)
        }
        return execute(request)
    }

    private suspend fun execute(request: NSMutableURLRequest): HttpResponseData =
        suspendCancellableCoroutine { continuation ->
            val task = session.dataTaskWithRequest(request) { data, response, error ->
                if (!continuation.isActive) return@dataTaskWithRequest
                if (error != null) {
                    continuation.resumeWithException(IOException(error.localizedDescription, null))
                    return@dataTaskWithRequest
                }
                val httpResp = response as? NSHTTPURLResponse
                if (httpResp == null) {
                    continuation.resumeWithException(IOException("Non-HTTP response"))
                    return@dataTaskWithRequest
                }
                val text = if (data != null) {
                    NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString().orEmpty()
                } else {
                    ""
                }
                continuation.resume(HttpResponseData(httpResp.statusCode.toInt(), text))
            }
            continuation.invokeOnCancellation { task.cancel() }
            task.resume()
        }
}

actual fun defaultHttpTransport(): HttpTransport = IosHttpTransport()
