package com.vayunmathur.library.ui

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * Renders an HTML fragment (an email body, typically) in an inline, wrap-content
 * [WebView].
 *
 * @param openLinksExternally hands tapped links to the system browser rather than
 *   letting them navigate inside this view. The view is sized to its content and
 *   embedded in a scrolling column, so in-place navigation would strand the user
 *   on a full web page inside a message. Only turn this off for content that is
 *   genuinely meant to be browsed in place.
 * @param javaScriptEnabled runs scripts in the content. Untrusted mail should
 *   pass false: a sender's script otherwise executes on open.
 * @param blockRemoteContent refuses every network fetch (images, CSS, fonts,
 *   iframes, script requests) - not just `<img>` like [blockRemoteImages] - so
 *   opening a message cannot ping the sender's server. Inline `cid:` images
 *   and `data:` URIs still load.
 */
@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    blockRemoteImages: Boolean = true,
    hideQuotes: Boolean = false,
    cidMap: Map<String, File> = emptyMap(),
    openLinksExternally: Boolean = true,
    javaScriptEnabled: Boolean = true,
    blockRemoteContent: Boolean = false,
) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()

    val backgroundHex = String.format("#%06X", 0xFFFFFF and backgroundColor.toArgb())
    val textHex = String.format("#%06X", 0xFFFFFF and onSurfaceColor.toArgb())
    val quoteCss = if (hideQuotes) {
        "blockquote, .gmail_quote, .yahoo_quoted, .moz-cite-prefix, .gmail_extra, .protonmail_quote { display: none !important; }"
    } else ""

    // Rewrite cid: -> https://cid.local/ to allow intercept without network permission
    val rewrittenHtml = remember(html, cidMap) {
        if (cidMap.isEmpty()) html
        else {
            var h = html
            // Replace src="cid:xxx" with src="https://cid.local/xxx" (case insensitive)
            h = h.replace(Regex("cid:", RegexOption.IGNORE_CASE), "https://cid.local/")
            h
        }
    }
    // One client instance for the life of the view, mutated in place. Swapping in a
    // new WebViewClient on every recomposition used to be how the cid map was
    // refreshed, and the guard that did it could never fire.
    val webClient = remember { HtmlTextWebViewClient() }
    webClient.cidMap = cidMap
    webClient.openLinksExternally = openLinksExternally
    webClient.blockRemoteContent = blockRemoteContent
    // Defence in depth alongside the request interceptor: the document itself
    // forbids any non-local fetch when remote content is blocked.
    val csp = if (blockRemoteContent) {
        "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
            "img-src data: https://cid.local; style-src 'unsafe-inline'; font-src data:\">"
    } else ""

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = javaScriptEnabled
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.blockNetworkImage = blockRemoteImages
                setBackgroundColor(backgroundColor.toArgb())

                webViewClient = webClient
            }
        },
        update = { webView ->
            webView.settings.blockNetworkImage = blockRemoteImages || blockRemoteContent
            webView.settings.javaScriptEnabled = javaScriptEnabled
            val htmlToLoad = if (cidMap.isEmpty()) html else rewrittenHtml
            val richCss = """
                h1,h2,h3 { margin:0.5em 0; font-weight:600; line-height:1.25; }
                h1 { font-size:1.6em; }
                h2 { font-size:1.35em; }
                h3 { font-size:1.15em; }
                blockquote { border-left:2px solid #ccc; margin:0 0 0 8px; padding-left:8px; color:#666; font-style:italic; }
                code { font-family:monospace; background:rgba(0,0,0,0.06); padding:2px 4px; border-radius:3px; word-break:break-word; }
                hr { border:none; border-top:1px solid #ccc; margin:12px 0; }
                pre { background:#f5f5f5; padding:8px; overflow-x:auto; border-radius:4px; font-family:monospace; white-space:pre-wrap; }
                ul, ol { margin:0.5em 0; padding-left:24px; }
                li { margin:0.25em 0; }
            """.trimIndent()

            if (isDark) {
                val darkModeHtml = """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        $csp
                        <style>
                            * { box-sizing: border-box; }
                            html, body { max-width: 100%; }
                            body {
                                margin: 0;
                                padding: 8px;
                                background-color: $backgroundHex;
                                font-family: sans-serif;
                                font-size: 14px;
                                line-height: 1.5;
                                word-wrap: break-word;
                                overflow-wrap: break-word;
                                filter: invert(1) hue-rotate(180deg);
                            }
                            img, video, iframe, svg, table { max-width: 100% !important; }
                            img, video, iframe, svg {
                                filter: invert(1) hue-rotate(180deg) brightness(0.9);
                                height: auto;
                            }
                            code {
                                filter: invert(1) hue-rotate(180deg);
                                background: rgba(255,255,255,0.12) !important;
                            }
                            $richCss
                            $quoteCss
                        </style>
                    </head>
                    <body>$htmlToLoad</body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("https://cid.local/", darkModeHtml, "text/html", "UTF-8", null)
            } else {
                val lightHtml = """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
                        $csp
                        <style>
                            * { box-sizing: border-box; }
                            html, body { max-width: 100%; }
                            body {
                                margin: 0;
                                padding: 8px;
                                color: $textHex;
                                background-color: $backgroundHex;
                                font-family: sans-serif;
                                font-size: 14px;
                                line-height: 1.5;
                                word-wrap: break-word;
                                overflow-wrap: break-word;
                            }
                            img, table { max-width: 100% !important; }
                            img { height: auto; }
                            $richCss
                            $quoteCss
                        </style>
                    </head>
                    <body>$htmlToLoad</body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL("https://cid.local/", lightHtml, "text/html", "UTF-8", null)
            }
        },
    )
}

/**
 * Serves `cid:` inline attachments from local files and keeps navigation out of
 * the view.
 *
 * Both fields are `var` because the composable owns a single instance for the
 * life of the [WebView] and updates it in place; replacing the client on
 * recomposition resets state the WebView keeps per-client.
 */
private class HtmlTextWebViewClient : WebViewClient() {

    var cidMap: Map<String, File> = emptyMap()
    var openLinksExternally: Boolean = true
    var blockRemoteContent: Boolean = false

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val uri = request?.url ?: return null
        if (uri.host != CID_HOST) return if (blockRemoteContent) emptyResponse() else null

        val cid = uri.lastPathSegment
            ?: uri.path?.removePrefix("/")?.substringAfterLast("/")
            ?: return null
        // A Content-ID can legitimately contain characters the URL had to encode.
        val decoded = try { java.net.URLDecoder.decode(cid, "UTF-8") } catch (_: Exception) { cid }
        val file = cidMap[decoded]
            ?: cidMap[decoded.removePrefix("<").removeSuffix(">")]
            ?: cidMap.entries.firstOrNull { it.key.equals(decoded, ignoreCase = true) }?.value
            ?: return null
        if (!file.exists()) return null

        return try {
            WebResourceResponse(guessMimeType(file), "utf-8", file.inputStream())
        } catch (_: Exception) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (!openLinksExternally) return false
        val uri = request?.url ?: return false
        // Our own inline-image host, and the synthetic document itself, are not links.
        if (uri.host == CID_HOST && uri.path.isNullOrEmpty()) return false
        if (uri.scheme?.lowercase() in IN_PLACE_SCHEMES) return false
        val context = view?.context ?: return false

        ExternalIntents.launch(
            context,
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Consumed either way: this view is sized to its content, so falling back to
        // in-place navigation when no app handles the link is worse than doing nothing.
        return true
    }

    private companion object {
        const val CID_HOST = "cid.local"
        val IN_PLACE_SCHEMES = setOf("data", "about", "javascript", "blob")

        /** Answers a blocked fetch locally so nothing reaches the network. */
        fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
    }
}

private fun guessMimeType(file: File): String {
    val name = file.name.lowercase()
    return when {
        name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
        name.endsWith(".png") -> "image/png"
        name.endsWith(".gif") -> "image/gif"
        name.endsWith(".webp") -> "image/webp"
        name.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }
}
