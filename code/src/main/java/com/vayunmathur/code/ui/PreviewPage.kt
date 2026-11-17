package com.vayunmathur.code.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.code.R
import com.vayunmathur.code.Route
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.domain.markdownToHtml
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack

/**
 * Renders the current file in a [WebView]: Markdown and HTML are shown rendered, and a JavaScript
 * file is executed with its `console.*` output captured inline. Other file types have no preview.
 */
@Composable
fun PreviewPage(viewModel: EditorViewModel, backStack: NavBackStack<Route>) {
    AppScaffold(title = stringResource(R.string.preview), backStack = backStack, scrollBehavior = appBarScrollBehavior()) { padding ->
        val tab = viewModel.currentTab
        if (tab == null) {
            Text(
                stringResource(R.string.preview_no_file),
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppScaffold
        }
        val text = tab.value.text
        val html = remember(text, tab.name) { buildPreviewHtml(text, tab.language, tab.name) }
        if (html == null) {
            Text(
                stringResource(R.string.preview_unsupported, tab.language.label),
                Modifier.padding(padding).padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            PreviewWebView(html, Modifier.padding(padding).fillMaxSize())
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewWebView(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                }
            }
        },
        update = { web ->
            if (web.tag != html) {
                web.tag = html
                web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}

/** Builds the HTML document to render for [text], or null if the file type has no preview. */
private fun buildPreviewHtml(text: String, language: Language, name: String): String? {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext == "md" || ext == "markdown" || language == Language.MARKDOWN -> styledDocument(markdownToHtml(text))
        ext == "html" || ext == "htm" -> text
        ext == "js" || ext == "mjs" || language == Language.JAVASCRIPT -> jsRunnerHtml(text)
        else -> null
    }
}

private fun styledDocument(body: String): String = """
    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      body { font-family: sans-serif; padding: 12px; line-height: 1.5; }
      pre { background: rgba(128,128,128,0.15); padding: 8px; overflow: auto; border-radius: 4px; }
      code { font-family: monospace; }
      blockquote { border-left: 3px solid rgba(128,128,128,0.5); margin: 0; padding-left: 12px; color: gray; }
    </style></head><body>$body</body></html>
""".trimIndent()

private fun jsRunnerHtml(code: String): String = """
    <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
    <style>body{font-family:monospace;padding:12px;white-space:pre-wrap;}</style></head>
    <body><pre id="__out"></pre><script>
      (function(){
        var o = document.getElementById('__out');
        function w(){ o.textContent += [].slice.call(arguments).map(String).join(' ') + '\n'; }
        console.log = w; console.info = w; console.warn = w; console.error = w;
        try { $code } catch (e) { w('Error: ' + e); }
      })();
    </script></body></html>
""".trimIndent()
