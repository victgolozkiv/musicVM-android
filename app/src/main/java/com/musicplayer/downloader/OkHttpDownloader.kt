package com.musicplayer.downloader

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpDownloader : Downloader() {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val body = request.dataToSend()
        val headers = request.headers()

        val builder = okhttp3.Request.Builder()
            .url(url)

        // Modern User-Agent aligned with ExoPlayer Nitro-Engine
        builder.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36")
        builder.addHeader("Accept-Language", "en-US,en;q=0.9,es;q=0.8")
        
        headers?.forEach { (key, values) ->
            if (!key.equals("User-Agent", ignoreCase = true) && 
                !key.equals("Accept-Encoding", ignoreCase = true)) {
                values.forEach { builder.addHeader(key, it) }
            }
        }

        if (method.equals("POST", ignoreCase = true)) {
            builder.post(body!!.toRequestBody())
        } else if (method.equals("GET", ignoreCase = true)) {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            val responseCode = response.code
            val responseMessage = response.message
            val responseBody = response.body?.string() ?: ""

            val responseHeaders = mutableMapOf<String, List<String>>()
            for (name in response.headers.names()) {
                responseHeaders[name] = response.headers.values(name)
            }

            return Response(responseCode, responseMessage, responseHeaders, responseBody, url)
        }
    }
}
