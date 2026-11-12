package com.vayunmathur.library.ui

/**
 * Everything besides the HTML fragment itself that shapes the document
 * [HtmlText] hands to its WebView.
 *
 * @property backgroundHex page background, `#RRGGBB`.
 * @property textHex body text colour, `#RRGGBB` (light theme only; dark mode
 *   inverts instead).
 * @property isDark selects the inverting dark-mode stylesheet.
 * @property hideQuotes hides quoted history (blockquote and the common client
 *   quote containers).
 * @property blockRemoteContent adds a Content-Security-Policy that forbids
 *   every non-local fetch.
 * @property fitContentToWidth makes fixed-width mail fit the screen width; see
 *   [HtmlTextDocument.FIT_TO_WIDTH_CSS].
 */
internal data class HtmlTextDocumentSpec(
    val backgroundHex: String,
    val textHex: String,
    val isDark: Boolean,
    val hideQuotes: Boolean = false,
    val blockRemoteContent: Boolean = false,
    val fitContentToWidth: Boolean = false,
)

/**
 * Builds the standalone document [HtmlText] loads around an HTML fragment.
 *
 * Kept free of Android and Compose so the wrapper (viewport, CSP, theme and
 * fit-to-width CSS) is unit-testable on the JVM.
 */
internal object HtmlTextDocument {

    /** The fixed viewport every document gets: layout at device width, 1:1. */
    const val DEFAULT_VIEWPORT = "width=device-width, initial-scale=1"

    /**
     * Viewport for [HtmlTextDocumentSpec.fitContentToWidth]: still laid out at
     * device width and opened at 1:1 so body text keeps its 14px size, but
     * the reader may pinch out to see anything that still overflows, or in to
     * read small print. Only an [HtmlText] that scrolls internally turns
     * pinch-zoom on; elsewhere the extra bounds are inert.
     */
    const val FIT_VIEWPORT =
        "width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=5, user-scalable=yes"

    /**
     * Fixed-width newsletter markup (a 600px `<table>` or `<div>`, inline
     * `min-width`, unbreakable URLs) otherwise lays out wider than a portrait
     * phone. These rules cap the common containers at the viewport width and
     * let long words and links break, so the page fits at its 1:1 scale
     * instead of being zoomed out to unreadable text or panned sideways.
     */
    const val FIT_TO_WIDTH_CSS = """
html, body { max-width: 100% !important; min-width: 0 !important; }
div, p, table, center, section, article, header, footer, main, aside, figure, blockquote, pre, ul, ol, li,
img, video, iframe, svg, canvas, embed, object, input, textarea { max-width: 100% !important; }
table, td, th, div { min-width: 0 !important; }
td, th, a { overflow-wrap: anywhere; word-break: normal; }
"""

    private const val QUOTE_CSS =
        "blockquote, .gmail_quote, .yahoo_quoted, .moz-cite-prefix, .gmail_extra, .protonmail_quote " +
            "{ display: none !important; }"

    private const val CSP_META = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; " +
        "img-src data: https://cid.local; style-src 'unsafe-inline'; font-src data:\">"

    /** A sender's own viewport tag; it would override ours, e.g. `width=600`. */
    private val SENDER_VIEWPORT = Regex(
        """<meta\b[^>]*\bname\s*=\s*["']?viewport\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    private val RICH_CSS = """
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

    /** Removes viewport tags the sender embedded, so [FIT_VIEWPORT] wins. */
    fun stripSenderViewport(html: String): String = html.replace(SENDER_VIEWPORT, "")

    /** The complete document for [body] under [spec]. */
    fun build(body: String, spec: HtmlTextDocumentSpec): String {
        val fit = spec.fitContentToWidth
        val content = if (fit) stripSenderViewport(body) else body
        val viewport = if (fit) FIT_VIEWPORT else DEFAULT_VIEWPORT
        val csp = if (spec.blockRemoteContent) CSP_META else ""
        val quoteCss = if (spec.hideQuotes) QUOTE_CSS else ""
        val fitCss = if (fit) FIT_TO_WIDTH_CSS else ""
        return if (spec.isDark) {
            darkDocument(content, spec.backgroundHex, viewport, csp, quoteCss + fitCss)
        } else {
            lightDocument(content, spec, viewport, csp, quoteCss + fitCss)
        }
    }

    private fun darkDocument(body: String, backgroundHex: String, viewport: String, csp: String, extraCss: String) = """
        <html>
        <head>
            <meta name="viewport" content="$viewport">
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
                $RICH_CSS
                $extraCss
            </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()

    private fun lightDocument(
        body: String,
        spec: HtmlTextDocumentSpec,
        viewport: String,
        csp: String,
        extraCss: String,
    ) = """
        <html>
        <head>
            <meta name="viewport" content="$viewport">
            $csp
            <style>
                * { box-sizing: border-box; }
                html, body { max-width: 100%; }
                body {
                    margin: 0;
                    padding: 8px;
                    color: ${spec.textHex};
                    background-color: ${spec.backgroundHex};
                    font-family: sans-serif;
                    font-size: 14px;
                    line-height: 1.5;
                    word-wrap: break-word;
                    overflow-wrap: break-word;
                }
                img, table { max-width: 100% !important; }
                img { height: auto; }
                $RICH_CSS
                $extraCss
            </style>
        </head>
        <body>$body</body>
        </html>
    """.trimIndent()
}
