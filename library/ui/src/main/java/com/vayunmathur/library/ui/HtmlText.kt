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
 * Renders an HTML fragment (an email body, typically) in a [WebView].
 *
 * By default the view is inline and wrap-content: it sizes itself to its
 * content for embedding in a scrolling column. That depends on the WebView
 * reporting its content height accurately, which it does not reliably do for
 * wide, fixed-layout mail; a reader that shows long untrusted HTML should use
 * [scrollInternally] instead.
 *
 * @param openLinksExternally hands tapped links to the system browser rather than
 *   letting them navigate inside this view. An inline view is sized to its
 *   content and embedded in a scrolling column, so in-place navigation would
 *   strand the user on a full web page inside a message. Only turn this off for
 *   content that is genuinely meant to be browsed in place.
 * @param javaScriptEnabled runs scripts in the content. Untrusted mail should
 *   pass false: a sender's script otherwise executes on open.
 * @param blockRemoteContent refuses every network fetch (images, CSS, fonts,
 *   iframes, script requests) - not just `<img>` like [blockRemoteImages] - so
 *   opening a message cannot ping the sender's server. Inline `cid:` images
 *   and `data:` URIs still load.
 * @param scrollInternally makes the view fill the bounded height it is given
 *   (e.g. `Modifier.weight(1f)` in a non-scrolling column) and scroll its own
 *   content, with a scrollbar and pinch-zoom. Never place such a view inside a
 *   vertically scrolling parent: the two scrollers would fight over every drag.
 * @param fitContentToWidth makes fixed-width layouts (600px newsletter tables,
 *   inline `min-width`, long unbreakable links) fit the view width at a
 *   readable 1:1 scale, and drops any viewport tag the sender embedded. See
 *   [HtmlTextDocument.FIT_TO_WIDTH_CSS].
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
    scrollInternally: Boolean = false,
    fitContentToWidth: Boolean = false,
) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()

    val backgroundHex = String.format("#%06X", 0xFFFFFF and backgroundColor.toArgb())
    val textHex = String.format("#%06X", 0xFFFFFF and onSurfaceColor.toArgb())

    // Rewrite cid: -> https://cid.local/ to allow intercept without network permission
    val rewrittenHtml = remember(html, cidMap) {
        if (cidMap.isEmpty()) html
        else html.replace(Regex("cid:", RegexOption.IGNORE_CASE), "https://cid.local/")
    }
    // One client instance for the life of the view, mutated in place. Swapping in a
    // new WebViewClient on every recomposition used to be how the cid map was
    // refreshed, and the guard that did it could never fire.
    val webClient = remember { HtmlTextWebViewClient() }
    webClient.cidMap = cidMap
    webClient.openLinksExternally = openLinksExternally
    webClient.blockRemoteContent = blockRemoteContent
    // Defence in depth alongside the request interceptor: the document itself
    // forbids any non-local fetch when remote content is blocked (CSP in the spec).
    val spec = HtmlTextDocumentSpec(
        backgroundHex = backgroundHex,
        textHex = textHex,
        isDark = isDark,
        hideQuotes = hideQuotes,
        blockRemoteContent = blockRemoteContent,
        fitContentToWidth = fitContentToWidth,
    )

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Before the view is attached: AndroidView sizes it from these params.
                applyHtmlTextLayout(scrollInternally)
                settings.loadWithOverviewMode = false
                settings.displayZoomControls = false
                setBackgroundColor(backgroundColor.toArgb())
                webViewClient = webClient
            }
        },
        update = { webView ->
            webView.applyHtmlTextLayout(scrollInternally)
            webView.settings.useWideViewPort = true
            webView.settings.blockNetworkImage = blockRemoteImages || blockRemoteContent
            webView.settings.javaScriptEnabled = javaScriptEnabled
            val document = HtmlTextDocument.build(rewrittenHtml, spec)
            // Only reload when what would be shown changed. `update` re-runs whenever
            // anything it captures changes (layout mode, a new cidMap instance with
            // the same files, ...), and reloading identical content resets the
            // scroll position and re-measures the view mid-read.
            val loadKey = "$blockRemoteImages|$javaScriptEnabled|$document"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.loadDataWithBaseURL("https://cid.local/", document, "text/html", "UTF-8", null)
            }
        },
    )
}

/**
 * Inline (wrap-content, no scrollbar, no zoom) or self-scrolling (fills its
 * bounds, scrollbar, pinch-zoom without on-screen buttons).
 */
private fun WebView.applyHtmlTextLayout(scrollInternally: Boolean) {
    val height = if (scrollInternally) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
    if (layoutParams?.width != ViewGroup.LayoutParams.MATCH_PARENT || layoutParams?.height != height) {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }
    isVerticalScrollBarEnabled = scrollInternally
    settings.builtInZoomControls = scrollInternally
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
