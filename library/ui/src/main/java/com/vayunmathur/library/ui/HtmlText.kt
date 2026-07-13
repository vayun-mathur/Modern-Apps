package com.vayunmathur.library.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
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

    // Sizing model: the WebView fills the available width (MATCH_PARENT) and wraps its
    // content height (WRAP_CONTENT), so the outer LazyColumn owns vertical scrolling.
    // Combined with useWideViewPort = true and the injected width=device-width viewport
    // meta, the layout viewport equals the WebView's own width, so content reflows to the
    // screen. The CSS below (box-sizing: border-box; max-width: 100% on media/tables) keeps
    // width:100% elements and wide media from spilling a few pixels past the edge, so
    // everything fits without horizontal scrolling.
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
                // Honor the injected width=device-width viewport (below) instead of the
                // WebView's default wide viewport, so content reflows to the screen width.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                // Privacy: don't load remote images (tracking pixels) unless asked.
                settings.blockNetworkImage = blockRemoteImages
                setBackgroundColor(backgroundColor.toArgb())
            }
        },
        update = { webView ->
            webView.settings.blockNetworkImage = blockRemoteImages
            if (isDark) {
                // In dark mode, invert the whole page to get light text on a dark
                // background, then re-invert media so images/video look correct.
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
                    <body>$html</body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, darkModeHtml, "text/html", "UTF-8", null)
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
                    <body>$html</body>
                    </html>
                """.trimIndent()
                webView.loadDataWithBaseURL(null, lightHtml, "text/html", "UTF-8", null)
            }
        },
    )
}
