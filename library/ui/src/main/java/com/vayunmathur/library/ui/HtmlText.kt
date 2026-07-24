package com.vayunmathur.library.ui

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

@Composable
fun HtmlText(
    html: String,
    modifier: Modifier = Modifier,
    blockRemoteImages: Boolean = true,
    hideQuotes: Boolean = false,
    cidMap: Map<String, File> = emptyMap(),
) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()

    val backgroundHex = String.format("#%06X", 0xFFFFFF and backgroundColor.toArgb())
    val textHex = String.format("#%06X", 0xFFFFFF and onSurfaceColor.toArgb())
    val quoteCss = if (hideQuotes) {
        "blockquote, .gmail_quote, .yahoo_quoted, .moz-cite-prefix, .gmail_extra { display: none !important; }"
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
    val cidMapState = remember(cidMap) { cidMap }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                isVerticalScrollBarEnabled = false
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.blockNetworkImage = blockRemoteImages
                setBackgroundColor(backgroundColor.toArgb())

                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        // Intercept our synthetic cid:// host
                        if (url.contains("cid.local")) {
                            val cid = request.url?.lastPathSegment
                                ?: request.url?.path?.removePrefix("/")?.substringAfterLast("/")
                                ?: return null
                            // CID may be url-encoded
                            val decodedCid = try { java.net.URLDecoder.decode(cid, "UTF-8") } catch (_: Exception) { cid }
                            // Try exact match, then stripped angle brackets variant
                            val file = cidMapState[decodedCid]
                                ?: cidMapState[decodedCid.removePrefix("<").removeSuffix(">")]
                                ?: cidMapState.entries.firstOrNull { it.key.equals(decodedCid, ignoreCase = true) }?.value
                                ?: return null
                            if (!file.exists()) return null
                            val mime = guessMimeType(file)
                            return try {
                                WebResourceResponse(mime, "utf-8", file.inputStream())
                            } catch (_: Exception) { null }
                        }
                        // Block remote if requested but allow cid interception already handled
                        return null
                    }
                }
            }
        },
        update = { webView ->
            webView.settings.blockNetworkImage = blockRemoteImages
            // Update client map when cidMap changes
            if (cidMapState !== cidMap) {
                // State already captured via remember; re-create client to capture new map
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (url.contains("cid.local")) {
                            val cid = request.url?.lastPathSegment ?: return null
                            val decodedCid = try { java.net.URLDecoder.decode(cid, "UTF-8") } catch (_: Exception) { cid }
                            val file = cidMap[decodedCid]
                                ?: cidMap[decodedCid.removePrefix("<").removeSuffix(">")]
                                ?: cidMap.entries.firstOrNull { it.key.equals(decodedCid, ignoreCase = true) }?.value
                                ?: return null
                            if (!file.exists()) return null
                            val mime = guessMimeType(file)
                            return try {
                                WebResourceResponse(mime, null, file.inputStream())
                            } catch (_: Exception) { null }
                        }
                        return null
                    }
                }
            }
            val htmlToLoad = if (cidMap.isEmpty()) html else rewrittenHtml
            if (isDark) {
                val darkModeHtml = """
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1">
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
