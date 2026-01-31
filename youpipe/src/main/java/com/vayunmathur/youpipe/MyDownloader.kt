package com.vayunmathur.youpipe;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.OkHttpClient;

public class MyDownloader extends Downloader {
    private final OkHttpClient client;

    public MyDownloader() {
        // Build the client with reasonable timeouts
        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public Response execute(Request request) throws IOException {
        String url = request.url();
        String method = request.httpMethod();
        byte[] body = request.dataToSend();
        Headers.Builder headersBuilder = new Headers.Builder();
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                headersBuilder.add(entry.getKey(), value);
            }
        }
        Headers headers = headersBuilder.build();

        // Construct the OkHttp Request
        okhttp3.Request okHttpRequest = new okhttp3.Request.Builder()
                .url(url)
                .method(method, body != null ? okhttp3.RequestBody.create(body) : null)
                .headers(headers)
                .build();

        // Execute and wrap the OkHttp Response into a NewPipe Response
        try (okhttp3.Response okHttpResponse = client.newCall(okHttpRequest).execute()) {
            int responseCode = okHttpResponse.code();
            String responseMessage = okHttpResponse.message();
            String responseBody = okHttpResponse.body() != null ? okHttpResponse.body().string() : "";

            return new Response(responseCode, responseMessage, okHttpResponse.headers().toMultimap(), responseBody, url);
        }
    }
}