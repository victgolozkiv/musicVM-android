package com.musicplayer.downloader;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

public class OkHttpDownloader extends Downloader {
    private final OkHttpClient client;

    public OkHttpDownloader() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(1, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        String url = request.url();
        String method = request.httpMethod();
        byte[] body = request.dataToSend();
        Map<String, List<String>> headers = request.headers();

        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url(url);

        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                for (String value : entry.getValue()) {
                    builder.addHeader(entry.getKey(), value);
                }
            }
        }

        if (method.equalsIgnoreCase("POST")) {
            builder.post(RequestBody.create(body));
        } else if (method.equalsIgnoreCase("GET")) {
            builder.get();
        }

        okhttp3.Response response = client.newCall(builder.build()).execute();
        int responseCode = response.code();
        String responseMessage = response.message();
        String responseBody = response.body() != null ? response.body().string() : "";

        Map<String, List<String>> responseHeaders = new HashMap<>();
        for (String name : response.headers().names()) {
            responseHeaders.put(name, response.headers().values(name));
        }

        return new Response(responseCode, responseMessage, responseHeaders, responseBody, url);
    }
}
