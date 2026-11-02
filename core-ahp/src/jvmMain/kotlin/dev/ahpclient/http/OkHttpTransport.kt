package dev.ahpclient.http

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OkHttpTransport(private val http: OkHttpClient = OkHttpClient()) : HttpTransport {
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.header(name, value) }
        http.newCall(builder.build()).await().use { response ->
            return HttpResponseData(response.code, response.body.string())
        }
    }

    override suspend fun postForm(url: String, headers: Map<String, String>, fields: Map<String, String>): HttpResponseData {
        val form = FormBody.Builder().apply { fields.forEach { (name, value) -> add(name, value) } }.build()
        val builder = Request.Builder().url(url).post(form)
        headers.forEach { (name, value) -> builder.header(name, value) }
        http.newCall(builder.build()).await().use { response ->
            return HttpResponseData(response.code, response.body.string())
        }
    }

    override suspend fun postJson(url: String, headers: Map<String, String>, jsonBody: String): HttpResponseData {
        val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        http.newCall(builder.build()).await().use { response ->
            return HttpResponseData(response.code, response.body.string())
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

actual fun defaultHttpTransport(): HttpTransport = OkHttpTransport()
