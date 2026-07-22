package com.vayunmathur.youpipe.util.sabr;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrProtocolException;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Legacy YouTube-page WebPoClient SABR PO-token provider.
 *
 * <p>The app no longer calls this provider. Normal playback and download use
 * {@link LocalDomPoTokenProvider}, which avoids loading the YouTube page and reuses the shared local
 * JavaScript runtime. This class is kept only as a legacy fallback/debug reference for the old
 * page-loaded WebPoClient pipeline.</p>
 */
@Deprecated
public final class WebViewPoTokenProvider implements SabrPoTokenProvider {

    private static final String TAG = "WebViewPoToken";
    private static final String ASSET = "sabr_webpo_client.js";
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private static final long TOKEN_TTL_MS = 6L * 60L * 60L * 1000L; // 6 hours
    // The WebView mint can occasionally run long. 60s + one retry avoids a token-less cold start.
    private static final long PIPELINE_TIMEOUT_MS = 60_000L;
    // Persist minted tokens across process restarts so an app cold-start can reuse a valid token.
    private static final String PREFS = "sabr_webpo_video_token_cache";
    private static final int READY_RETRIES = 20;
    private static final long READY_POLL_MS = 250L;

    private static final class CachedToken {
        private final byte[] token;
        private final long mintedAtMs;

        CachedToken(final byte[] token, final long mintedAtMs) {
            this.token = token;
            this.mintedAtMs = mintedAtMs;
        }
    }

    private final Context appContext;
    private final Handler mainHandler;
    private final android.content.SharedPreferences prefs;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    // one lock per videoId so two callers (pre-warm + pump) don't both fire the ~45s WebView mint
    // for the same video. second one just waits and takes the cached token.
    private final Map<String, Object> mintLocks = new ConcurrentHashMap<>();
    public WebViewPoTokenProvider(final Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.prefs = this.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public byte[] getPoToken(final YoutubeSabrInfo info, final YoutubeSabrStreamState streamState)
            throws SabrProtocolException {
        return getPoToken(info, streamState, false);
    }

    @Nullable
    @Override
    public byte[] getPoToken(final YoutubeSabrInfo info, final YoutubeSabrStreamState streamState,
                             final boolean forceRefresh) throws SabrProtocolException {
        final String videoId = info.getVideoId();
        Log.i(TAG, "get video=" + videoId + " force=" + forceRefresh
                + " thread=" + Thread.currentThread().getName());
        if (forceRefresh) {
            // Server rejected the cached token: drop it (memory + disk) and mint fresh.
            cache.remove(videoId);
            prefs.edit().remove(videoId).apply();
        }
        synchronized (mintLocks.computeIfAbsent(videoId, k -> new Object())) {
            final long now = System.currentTimeMillis();
            CachedToken cached = cache.get(videoId);
            if (cached == null) {
                cached = diskLoad(videoId); // survive process restart, skip the ~45s mint
                if (cached != null) {
                    cache.put(videoId, cached);
                }
            }
            if (cached != null && now - cached.mintedAtMs < TOKEN_TTL_MS) {
                Log.i(TAG, "cache hit video=" + videoId + " bytes=" + cached.token.length
                        + " ageMs=" + (now - cached.mintedAtMs));
                return cached.token;
            }
            if (Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "mint skipped: interrupted video=" + videoId);
                throw new SabrProtocolException("PO token mint interrupted before start");
            }
            // One retry avoids failing playback on a transient WebPoClient error.
            final String contentBinding = info.getVideoId();
            Log.i(TAG, "mint start video=" + videoId + " binding=video_id");
            String tokenB64 = mintBlocking(contentBinding);
            if (Thread.currentThread().isInterrupted()) {
                throw new SabrProtocolException("PO token mint interrupted after pipeline");
            }
            if (tokenB64 == null || tokenB64.isEmpty()) {
                Log.w(TAG, "PO token mint returned null, retrying once for " + videoId);
                tokenB64 = mintBlocking(contentBinding);
                if (Thread.currentThread().isInterrupted()) {
                    throw new SabrProtocolException("PO token mint interrupted after retry");
                }
            }
            if (tokenB64 == null || tokenB64.isEmpty()) {
                Log.e(TAG, "PO token mint failed after retry video=" + videoId);
                return null;
            }
            final byte[] token;
            try {
                token = Base64.getUrlDecoder().decode(tokenB64);
            } catch (final IllegalArgumentException e) {
                Log.e(TAG, "could not decode PO token", e);
                return null;
            }
            cache.put(videoId, new CachedToken(token, now));
            diskSave(videoId, tokenB64, now);
            Log.i(TAG, "mint complete video=" + videoId + " bytes=" + token.length);
            return token;
        }
    }

    /**
     * True if a non-expired PO token for this video is already in memory or on disk, WITHOUT minting.
     * Lets a caller pre-load metadata cheaply when we've recently played this video (cold-restore /
     * re-resolve) while NOT blocking the first-ever play on the ~45s mint.
     */
    public boolean hasCachedToken(final String videoId) {
        final CachedToken mem = cache.get(videoId);
        if (mem != null && System.currentTimeMillis() - mem.mintedAtMs < TOKEN_TTL_MS) {
            return true;
        }
        return diskLoad(videoId) != null;
    }

    @Nullable
    private CachedToken diskLoad(final String videoId) {
        final String v = prefs.getString(videoId, null);
        if (v == null) {
            return null;
        }
        final int sep = v.indexOf('|');
        if (sep <= 0) {
            return null;
        }
        try {
            final long mintedAt = Long.parseLong(v.substring(0, sep));
            if (System.currentTimeMillis() - mintedAt >= TOKEN_TTL_MS) {
                prefs.edit().remove(videoId).apply();
                return null;
            }
            return new CachedToken(Base64.getUrlDecoder().decode(v.substring(sep + 1)), mintedAt);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private void diskSave(final String videoId, final String tokenB64, final long mintedAt) {
        // commit() (sync) not apply(): the token must hit disk before a fast force-stop/process kill,
        // else an app cold-start re-mints (~45s) even though a valid token was just minted.
        prefs.edit().putString(videoId, mintedAt + "|" + tokenB64).commit();
    }

    @Nullable
    private String mintBlocking(final String contentBinding) throws SabrProtocolException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean canceled = new AtomicBoolean(false);
        final AtomicReference<String> tokenRef = new AtomicReference<>();
        final AtomicReference<WebView> webViewRef = new AtomicReference<>();
        final AtomicReference<String> stage = new AtomicReference<>("posting_create");
        final AtomicReference<String> detail = new AtomicReference<>("none");
        final AtomicReference<Throwable> failureRef = new AtomicReference<>();
        final long startedAt = System.currentTimeMillis();

        mainHandler.post(() -> {
            if (canceled.get()) {
                Log.w(TAG, "create canceled before main-thread start");
                latch.countDown();
                return;
            }
            try {
                stage.set("creating_webview");
                Log.i(TAG, "creating WebView mainThread="
                        + (Looper.myLooper() == Looper.getMainLooper()));
                final WebView webView = createWebView(contentBinding, tokenRef, latch, canceled,
                        stage, detail, failureRef);
                if (canceled.get()) {
                    Log.w(TAG, "create completed after cancellation");
                    destroyWebView(webView);
                    latch.countDown();
                } else {
                    webViewRef.set(webView);
                    Log.i(TAG, "WebView created and load requested");
                }
            } catch (final Exception e) {
                stage.set("create_failed");
                failureRef.set(e);
                Log.e(TAG, "failed to start WebView pipeline", e);
                latch.countDown();
            }
        });

        try {
            if (!latch.await(PIPELINE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "pipeline timeout stage=" + stage.get()
                        + " elapsedMs=" + (System.currentTimeMillis() - startedAt)
                        + " webView=" + (webViewRef.get() != null)
                        + " detail=" + detail.get());
                throw new SabrProtocolException("PO token pipeline timed out at " + stage.get()
                        + ", detail=" + detail.get());
            }
            Log.i(TAG, "pipeline released stage=" + stage.get()
                    + " elapsedMs=" + (System.currentTimeMillis() - startedAt)
                    + " token=" + (tokenRef.get() == null ? "null" : "present"));
            final Throwable failure = failureRef.get();
            if (failure != null) {
                throw new SabrProtocolException("PO token pipeline failed at " + stage.get()
                        + ", detail=" + detail.get() + ": " + failure.getMessage(), failure);
            }
            if (tokenRef.get() == null || tokenRef.get().isEmpty()) {
                throw new SabrProtocolException("PO token pipeline returned no token at "
                        + stage.get() + ", detail=" + detail.get());
            }
        } catch (final InterruptedException e) {
            final String interruptedStage = stage.get();
            Log.w(TAG, "pipeline interrupted stage=" + interruptedStage
                    + " elapsedMs=" + (System.currentTimeMillis() - startedAt)
                    + " detail=" + detail.get(), e);
            Thread.currentThread().interrupt();
            throw new SabrProtocolException("PO token pipeline interrupted at "
                    + interruptedStage + ", detail=" + detail.get(), e);
        } finally {
            canceled.set(true);
            mainHandler.post(() -> destroyWebView(webViewRef.getAndSet(null)));
        }
        return tokenRef.get();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView(final String contentBinding,
                                  final AtomicReference<String> tokenRef,
                                  final CountDownLatch latch,
                                  final AtomicBoolean canceled,
                                  final AtomicReference<String> stage,
                                  final AtomicReference<String> detail,
                                  final AtomicReference<Throwable> failureRef) {
        final WebView webView = new WebView(appContext);
        final AtomicBoolean injected = new AtomicBoolean(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && WebView.getCurrentWebViewPackage() != null) {
            Log.i(TAG, "WebView package=" + WebView.getCurrentWebViewPackage().packageName
                    + " version=" + WebView.getCurrentWebViewPackage().versionName);
            detail.set("webView=" + WebView.getCurrentWebViewPackage().packageName + '/'
                    + WebView.getCurrentWebViewPackage().versionName);
        }
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(DESKTOP_UA);
        webView.addJavascriptInterface(new Bridge(tokenRef, latch, canceled, stage, detail,
                        failureRef),
                "SabrPocBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(final ConsoleMessage message) {
                Log.i(TAG, "console " + message.messageLevel() + " " + message.message()
                        + " @" + message.sourceId() + ':' + message.lineNumber());
                detail.set("console=" + limit(message.message(), 300)
                        + " level=" + message.messageLevel() + " line=" + message.lineNumber());
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(final WebView view,
                                                              final WebResourceRequest request) {
                final String url = request.getUrl().toString();
                if (url.contains("/js/th/")) {
                    Log.i(TAG, "intercept interpreter url=" + url);
                    return fetchWithCors(url);
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(final WebView view, final String url) {
                super.onPageFinished(view, url);
                Log.i(TAG, "page finished url=" + url + " canceled=" + canceled.get());
                if (canceled.get() || url == null || !url.contains("youtube.com")
                        || !injected.compareAndSet(false, true)) {
                    return;
                }
                stage.set("page_finished");
                waitForReadyThenInject(view, contentBinding, 0, canceled, stage, detail,
                        failureRef, latch);
            }

            @Override
            public void onPageCommitVisible(final WebView view, final String url) {
                super.onPageCommitVisible(view, url);
                Log.i(TAG, "page commit url=" + url + " canceled=" + canceled.get());
                if (canceled.get() || url == null || !url.contains("youtube.com")
                        || !injected.compareAndSet(false, true)) {
                    return;
                }
                stage.set("page_committed");
                waitForReadyThenInject(view, contentBinding, 0, canceled, stage, detail,
                        failureRef, latch);
            }

            @Override
            public void onReceivedError(final WebView view, final WebResourceRequest request,
                                        final WebResourceError error) {
                super.onReceivedError(view, request, error);
                Log.w(TAG, "resource error main=" + request.isForMainFrame()
                        + " code=" + error.getErrorCode() + " description="
                        + error.getDescription() + " url=" + request.getUrl());
                if (request.isForMainFrame() && failureRef.compareAndSet(null,
                        new IllegalStateException("WebView main page error "
                                + error.getErrorCode() + ": " + error.getDescription()))) {
                    stage.set("main_page_failed");
                    latch.countDown();
                }
            }
        });
        stage.set("loading_page");
        Log.i(TAG, "load page");
        webView.loadUrl("https://www.youtube.com?themeRefresh=1");
        return webView;
    }

    private void waitForReadyThenInject(final WebView view, final String contentBinding,
                                        final int attempt,
                                        final AtomicBoolean canceled,
                                        final AtomicReference<String> stage,
                                        final AtomicReference<String> detail,
                                        final AtomicReference<Throwable> failureRef,
                                        final CountDownLatch latch) {
        if (canceled.get()) {
            Log.w(TAG, "ready poll canceled attempt=" + attempt);
            return;
        }
        stage.set("ready_poll_" + attempt);
        view.evaluateJavascript("document.readyState", value -> {
            if (canceled.get()) {
                Log.w(TAG, "ready result canceled attempt=" + attempt);
                return;
            }
            final boolean complete = value != null && value.contains("complete");
            Log.i(TAG, "ready attempt=" + attempt + " value=" + value
                    + " complete=" + complete);
            detail.set("readyState=" + value + " attempt=" + attempt);
            if (complete || attempt >= READY_RETRIES) {
                stage.set("injecting_binding");
                view.evaluateJavascript(
                        "window.__SABR_WEBPO_CONTENT_BINDING=" + jsString(contentBinding) + ";",
                        result -> Log.i(TAG, "binding injected result=" + result));
                stage.set("injecting_script");
                final String script = loadPipelineScript();
                if (script.isEmpty()) {
                    failureRef.compareAndSet(null,
                            new IllegalStateException("WebPo pipeline asset is empty"));
                    stage.set("script_load_failed");
                    latch.countDown();
                    return;
                }
                view.evaluateJavascript(script, result -> {
                    stage.set("waiting_bridge");
                    Log.i(TAG, "script injected result=" + result);
                });
            } else {
                mainHandler.postDelayed(
                        () -> waitForReadyThenInject(view, contentBinding, attempt + 1, canceled,
                                stage, detail, failureRef, latch),
                        READY_POLL_MS);
            }
        });
    }

    private static void destroyWebView(@Nullable final WebView webView) {
        if (webView == null) {
            return;
        }
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.removeAllViews();
            webView.destroy();
        } catch (final Exception ignored) {
            // best effort
        }
    }

    private static String jsString(final String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String limit(@Nullable final String value, final int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String loadPipelineScript() {
        try (InputStream in = appContext.getAssets().open(ASSET);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toString("UTF-8");
        } catch (final Exception e) {
            Log.e(TAG, "could not read pipeline asset", e);
            return "";
        }
    }

    @Nullable
    private static WebResourceResponse fetchWithCors(final String url) {
        try {
            final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestProperty("User-Agent", DESKTOP_UA);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            final int code = connection.getResponseCode();
            final InputStream body = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            final String contentType = connection.getContentType();
            String mime = "application/javascript";
            if (contentType != null) {
                final int sep = contentType.indexOf(';');
                mime = sep > 0 ? contentType.substring(0, sep).trim() : contentType.trim();
            }
            final Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            final WebResourceResponse response = new WebResourceResponse(mime, "UTF-8", body);
            response.setStatusCodeAndReasonPhrase(code, code >= 400 ? "ERROR" : "OK");
            response.setResponseHeaders(headers);
            return response;
        } catch (final Exception e) {
            Log.e(TAG, "interpreter native fetch failed", e);
            return null;
        }
    }

    private static final class Bridge {
        private final AtomicReference<String> tokenRef;
        private final CountDownLatch latch;
        private final AtomicBoolean canceled;
        private final AtomicReference<String> stage;
        private final AtomicReference<String> detail;
        private final AtomicReference<Throwable> failureRef;

        Bridge(final AtomicReference<String> tokenRef, final CountDownLatch latch,
               final AtomicBoolean canceled, final AtomicReference<String> stage,
               final AtomicReference<String> detail,
               final AtomicReference<Throwable> failureRef) {
            this.tokenRef = tokenRef;
            this.latch = latch;
            this.canceled = canceled;
            this.stage = stage;
            this.detail = detail;
            this.failureRef = failureRef;
        }

        @JavascriptInterface
        public void onStage(final String nextStage, final String nextDetail) {
            stage.set("js_" + limit(nextStage, 80));
            detail.set(limit(nextDetail, 500));
            Log.i(TAG, "JS stage=" + stage.get() + " detail=" + detail.get());
        }

        @JavascriptInterface
        public void onResult(final String json) {
            stage.set("bridge_called");
            Log.i(TAG, "bridge called canceled=" + canceled.get()
                    + " jsonLength=" + (json == null ? -1 : json.length()));
            try {
                if (canceled.get()) {
                    Log.w(TAG, "bridge result ignored after cancellation");
                    return;
                }
                final JSONObject obj = new JSONObject(json);
                if (obj.optBoolean("ok", false)) {
                    final String token = obj.optString("poToken", null);
                    tokenRef.set(token);
                    stage.set("bridge_success");
                    Log.i(TAG, "bridge success tokenB64Length="
                            + (token == null ? -1 : token.length()));
                } else {
                    stage.set("bridge_failed");
                    final String error = obj.optString("error", "unknown");
                    failureRef.compareAndSet(null, new IllegalStateException(error));
                    Log.w(TAG, "PO token pipeline failed: " + error);
                }
            } catch (final Exception e) {
                stage.set("bridge_parse_failed");
                failureRef.compareAndSet(null, e);
                Log.e(TAG, "could not parse pipeline result", e);
            } finally {
                latch.countDown();
            }
        }
    }
}
