package com.vayunmathur.library.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun HtmlText(html: String, modifier: Modifier = Modifier, blockRemoteImages: Boolean = true, hideQuotes: Boolean = false) {
    val backgroundColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val isDark = isSystemInDarkTheme()

    // Convert colors to hex strings for CSS
    val backgroundHex = String.format("#%06X", 0xFFFFFF and backgroundColor.toArgb())
    val textHex = String.format("#%06X", 0xFFFFFF and onSurfaceColor.toArgb())
    val quoteCss = if (hideQuotes) {
        "blockquote, .gmail_quote, .yahoo_quoted, .moz-cite-prefix, .gmail_extra { display: none !important; }"
    } else ""

    // Let Compose own all scrolling: the WebView is sized to WRAP_CONTENT in both
    // dimensions so it never scrolls internally. The outer LazyColumn handles vertical
    // scrolling, and the horizontalScroll below handles wide content. The WebView lays
    // out at its natural (arbitrary) width but is forced to at least fill the viewport
    // so narrow/fluid emails still span the screen.
    BoxWithConstraints(modifier) {
        val minContentWidthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        isScrollContainer = false
                        overScrollMode = WebView.OVER_SCROLL_NEVER
                        settings.javaScriptEnabled = true
                        // Disable the WebView's own fit-to-width / pan / zoom behavior so
                        // content renders at its natural width and Compose does the scrolling.
                        settings.loadWithOverviewMode = false
                        settings.useWideViewPort = false
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        // Privacy: don't load remote images (tracking pixels) unless asked.
                        settings.blockNetworkImage = blockRemoteImages
                        setBackgroundColor(backgroundColor.toArgb())
                    }
                },
                update = { webView ->
                    webView.minimumWidth = minContentWidthPx
                    webView.settings.blockNetworkImage = blockRemoteImages
                    if (isDark) {
                        // In dark mode, use JavaScript to force all text to be light colored
                        // and invert the entire page, then re-invert images
                        val darkModeHtml = """
                            <html>
                            <head>
                                <style>
                                    body {
                                        margin: 0;
                                        padding: 8px;
                                        background-color: $backgroundHex;
                                        font-family: sans-serif;
                                        font-size: 14px;
                                        line-height: 1.5;
                                        /* Invert everything, then we'll un-invert images */
                                        filter: invert(1) hue-rotate(180deg);
                                    }
                                    img, video, iframe, svg {
                                        /* Re-invert media elements */
                                        filter: invert(1) hue-rotate(180deg) brightness(0.9);
                                        max-width: 100%;
                                        height: auto;
                                    }
                                    $quoteCss
                                </style>
                            </head>
                            <body>$html</body>
                            </html>
                        """.trimIndent()
                        webView.loadDataWithBaseURL(null, darkModeHtml, "text/html", "UTF-8", null)
                    } else {
                        val lightHtml = """
                            <html>
                            <head>
                                <style>
                                    body {
                                        margin: 0;
                                        padding: 8px;
                                        color: $textHex;
                                        background-color: $backgroundHex;
                                        font-family: sans-serif;
                                        font-size: 14px;
                                        line-height: 1.5;
                                    }
                                    img { max-width: 100%; height: auto; }
                                    $quoteCss
                                </style>
                            </head>
                            <body>$html</body>
                            </html>
                        """.trimIndent()
                        webView.loadDataWithBaseURL(null, lightHtml, "text/html", "UTF-8", null)
                    }
                }
            )
        }
    }
}
