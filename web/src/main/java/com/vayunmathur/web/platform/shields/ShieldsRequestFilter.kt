package com.vayunmathur.web.platform.shields

import android.content.Context
import android.net.Uri
import android.text.TextUtils
import com.vayunmathur.library.log.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vayunmathur.web.R
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.LanPolicy
import com.vayunmathur.web.domain.LocalNetwork
import com.vayunmathur.web.domain.shields.RequestGate
import com.vayunmathur.web.domain.shields.ResourceTypes
import java.io.ByteArrayInputStream
import java.util.Base64

private const val TAG = "ShieldsRequestFilter"

/** Served for synthetic responses; WebView may discard non-2xx bodies in favour of its own error page. */
private const val HTTP_OK = 200

/** Empty body for a blocked image; anything else can be zero bytes. */
private val EMPTY_GIF: ByteArray = Base64.getDecoder()
    .decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")

/**
 * The single gate every network request passes through.
 *
 * It lives apart from [ShieldsWebViewClient] because a `WebViewClient` is not the only caller:
 * [ShieldsServiceWorkerClient] filters service-worker fetches, which bypass the WebView client
 * entirely. Keeping one body is the same reason `WebViewBrowser` and `PwaActivity` share a
 * client — so the two cannot drift.
 */
object ShieldsRequestFilter {

    /** Process-wide, and deliberately created before any WebView exists. */
    private val lanPolicy: LanPolicy by lazy { LanPolicy(InetHostResolver) }

    /**
     * A response to serve instead of making the request, or null to let it through.
     *
     * @param pageUrl the document the request belongs to, for first/third-party detection
     */
    fun intercept(
        context: Context,
        request: WebResourceRequest,
        pageUrl: String,
        shieldsFor: (host: String) -> EffectiveShields,
        onBlocked: (pageUrl: String, blockedUrl: String) -> Unit,
    ): WebResourceResponse? {
        val url = request.url.toString()
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        // Above the main-frame early return below, otherwise a cleartext navigation — the
        // common case — would never be gated at all.
        cleartextBlockResponse(context, request, scheme)?.let { return it }

        if (request.isForMainFrame) return null
        return shieldBlockResponse(request, url, pageUrl, shieldsFor, onBlocked, scheme)
    }

    /** Non-null when a cleartext request targets a host the LAN policy forbids. */
    private fun cleartextBlockResponse(
        context: Context,
        request: WebResourceRequest,
        scheme: String?,
    ): WebResourceResponse? {
        if (scheme != "http" || lanPolicy.allowsCleartext(request.url.toString())) return null
        val host = LocalNetwork.hostOf(request.url.toString())
        Log.dev(TAG, "blocked public cleartext request to $host")
        return if (request.isForMainFrame) {
            blockedPageResponse(context, host)
        } else {
            val type = ResourceTypes.of(
                request.url.toString(),
                isMainFrame = false,
                headers = request.requestHeaders,
            )
            emptyResponse(type)
        }
    }

    /** Non-null when the shields engine blocks this sub-resource request. */
    private fun shieldBlockResponse(
        request: WebResourceRequest,
        url: String,
        pageUrl: String,
        shieldsFor: (host: String) -> EffectiveShields,
        onBlocked: (pageUrl: String, blockedUrl: String) -> Unit,
        scheme: String?,
    ): WebResourceResponse? {
        val source = pageUrl.ifEmpty { url }
        val shields = shieldsFor(hostOf(source))
        // The gate is checked before touching requestHeaders / ResourceTypes:
        // with the engine still loading, ShieldsEngine.check can only return
        // null, so classifying the resource would be pure waste per request.
        if (!RequestGate.shouldShieldCheck(scheme, false, ShieldsEngine.ready, shields.blockTrackers)) {
            return null
        }

        val type = ResourceTypes.of(url, isMainFrame = false, headers = request.requestHeaders)
        val result = ShieldsEngine.check(url, source, type) ?: return null
        if (!result.blocked) return null

        onBlocked(source, url)
        return result.redirect?.let { dataUrlResponse(it) } ?: emptyResponse(type)
    }

    /**
     * An empty 200 rather than a failure: a blocked script that 404s can send a page down
     * an error path, whereas an empty body usually just no-ops. The content type has to
     * match what the element expected or the browser logs a MIME error and, for images,
     * shows a broken-image placeholder.
     */
    fun emptyResponse(type: String): WebResourceResponse {
        val (mime, body) = when (type) {
            "image" -> "image/gif" to EMPTY_GIF
            "script" -> "application/javascript" to ByteArray(0)
            "stylesheet" -> "text/css" to ByteArray(0)
            "media" -> "video/mp4" to ByteArray(0)
            "xmlhttprequest" -> "application/json" to ByteArray(0)
            "subdocument" -> "text/html" to ByteArray(0)
            else -> "text/plain" to ByteArray(0)
        }
        return WebResourceResponse(mime, "utf-8", HTTP_OK, "OK", emptyMap(), ByteArrayInputStream(body))
    }

    /** Serves the body of a `$redirect` rule, which the engine hands back as a data URL. */
    private fun dataUrlResponse(dataUrl: String): WebResourceResponse? = runCatching {
        val header = dataUrl.substringBefore(',', "")
        val payload = dataUrl.substringAfter(',', "")
        val mime = header.removePrefix("data:").substringBefore(';').ifEmpty { "text/plain" }
        val bytes = if (header.endsWith(";base64")) {
            Base64.getDecoder().decode(payload)
        } else {
            Uri.decode(payload).toByteArray()
        }
        WebResourceResponse(mime, "utf-8", HTTP_OK, "OK", emptyMap(), ByteArrayInputStream(bytes))
    }.onFailure { e ->
        Log.status(TAG, "malformed redirect resource", e)
    }.getOrNull()

    /**
     * The interstitial for a blocked navigation. An empty body would render as a mystery
     * blank page, and the app has no other error-page infrastructure.
     *
     * Status **200**, not 4xx: WebView may discard the body of a non-2xx response and
     * substitute its own error page.
     */
    private fun blockedPageResponse(context: Context, host: String): WebResourceResponse {
        val title = context.getString(R.string.cleartext_blocked_title)
        // Attacker-controlled text going into a document we render.
        val message = context.getString(R.string.cleartext_blocked_message, TextUtils.htmlEncode(host))
        val html = """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title</title>
            <style>
              body { font-family: sans-serif; margin: 0; padding: 3rem 1.5rem;
                     line-height: 1.5; background: #fdfcfb; color: #1b1b1b; }
              h1 { font-size: 1.2rem; margin: 0 0 0.75rem; }
              p { font-size: 0.95rem; margin: 0; color: #5c5c5c; }
              @media (prefers-color-scheme: dark) {
                body { background: #131314; color: #e3e3e3; }
                p { color: #a8a8a8; }
              }
            </style></head>
            <body><h1>$title</h1><p>$message</p></body></html>
        """.trimIndent()
        return WebResourceResponse(
            "text/html", "utf-8", HTTP_OK, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()),
        )
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
}
