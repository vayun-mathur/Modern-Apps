package com.vayunmathur.web.ui

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.updateStorageFootprint

/**
 * Forwards the system light/dark setting to page content as `prefers-color-scheme`.
 *
 * WebView reads this from the hosting activity theme's `android:isLightTheme`, which
 * `Theme.Web` flips through its `values-night` variant. Enabling algorithmic darkening is what
 * opts the WebView into honouring that flag; it additionally auto-darkens pages that ship no
 * dark stylesheet of their own, and leaves pages that do handle dark mode to style themselves.
 */
internal fun WebSettings.applySystemDarkMode() {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        // Some WebView APKs report the feature but still throw when it is set;
        // darkening is cosmetic, so failing to enable it must not crash page setup.
        runCatching { WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true) }
    }
}

/**
 * The inputs [applyWebViewSettings] actually reads. Recomposition during
 * a load (progress, title, URL, blocked count) used to re-apply every
 * setting — a default-user-agent lookup, a cookie-manager call, a
 * geolocation-path call and a reflective Safe Browsing probe — on the
 * main thread, several times per page. Callers compare specs and only
 * re-apply when one of these values changed.
 */
internal data class WebViewSettingsSpec(
    val jsEnabled: Boolean,
    val cacheMode: Int,
    val desktopMode: Boolean,
    val blockThirdPartyCookies: Boolean,
    val httpsOnly: Boolean,
)

internal fun webViewSettingsSpec(viewModel: WebViewModel): WebViewSettingsSpec {
    val globalShields = com.vayunmathur.web.domain.EffectiveShields.resolve(viewModel.shields)
    return WebViewSettingsSpec(
        jsEnabled = viewModel.jsEnabled,
        cacheMode = viewModel.cacheMode.webSettingsValue,
        desktopMode = viewModel.desktopMode,
        blockThirdPartyCookies = viewModel.blockThirdPartyCookies,
        httpsOnly = globalShields.httpsOnly,
    )
}

/** Process-wide: the default UA is a system string, identical for every WebView in the process. */
@Volatile
private var cachedDefaultUserAgent: String? = null

private fun defaultUserAgent(context: android.content.Context): String =
    cachedDefaultUserAgent ?: WebSettings.getDefaultUserAgent(context).also { cachedDefaultUserAgent = it }

private fun applySafeBrowsing(settings: WebSettings) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        runCatching { WebSettingsCompat.setSafeBrowsingEnabled(settings, true) }
    }
}

internal fun applyWebViewSettings(webView: WebView, viewModel: WebViewModel) {
    val settings = webView.settings

    settings.javaScriptEnabled = viewModel.jsEnabled
    settings.javaScriptCanOpenWindowsAutomatically = viewModel.jsEnabled

    // DOM storage = localStorage / sessionStorage, persisted in WebView data dir
    settings.domStorageEnabled = true
    settings.databaseEnabled = true

    // HTTP cache — user selectable for speed / offline
    settings.cacheMode = viewModel.cacheMode.webSettingsValue

    // No local-file browsing: programmatic loads are http(s)/about:blank-gated
    // (BrowserUtils.canLoadInWebView) and no web feature loads file:// or
    // content:// into the page. File-chooser uploads hand content URIs back
    // via WebChromeClient.onShowFileChooser, which does not need these.
    // With JS enabled, file/content access would let a page read local files.
    settings.allowFileAccess = false
    settings.allowContentAccess = false

    // Offscreen pre-raster speeds first paint
    settings.offscreenPreRaster = true

    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true

    // Shields owns the mixed-content decision (do not hardcode NEVER_ALLOW
    // here): httpsOnly is httpsUpgrade && AGGRESSIVE, and the shipped default
    // preset is AGGRESSIVE_DEFAULTS, so the default resolves to NEVER_ALLOW.
    // A user who lowers a site/global to the non-aggressive STANDARD preset
    // (httpsOnly=false) deliberately opts into COMPATIBILITY_MODE so pages
    // with a few http:// images still render.
    val globalShields = com.vayunmathur.web.domain.EffectiveShields.resolve(viewModel.shields)
    settings.mixedContentMode = if (globalShields.httpsOnly) {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
    } else {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }

    // Geolocation DB persists across loads
    settings.setGeolocationEnabled(true)
    try {
        @Suppress("DEPRECATION")
        settings.setGeolocationDatabasePath(webView.context.filesDir.absolutePath)
    } catch (_: Exception) {}

    settings.setSupportMultipleWindows(true)

    try {
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, !viewModel.blockThirdPartyCookies)
    } catch (_: Exception) {}

    settings.userAgentString = if (viewModel.desktopMode) {
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    } else {
        defaultUserAgent(webView.context)
    }

    settings.mediaPlaybackRequiresUserGesture = false

    settings.applySystemDarkMode()

    applySafeBrowsing(settings)
}

internal fun WebView.evalJsForStorageInfo(origin: String, cookieCount: Int, vm: WebViewModel) {
    evaluateJavascript(
        """(function(){
            try{
                var hasLS=false; try{hasLS=window.localStorage&&window.localStorage.length>0;}catch(e){}
                var hasIDB=!!window.indexedDB;
                var hasSW=!!navigator.serviceWorker&&!!navigator.serviceWorker.controller;
                var est=0;
                try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i); est+=(k?k.length:0)+(localStorage.getItem(k)?localStorage.getItem(k).length:0);} }catch(e){}
                return JSON.stringify({hasLS:hasLS,hasIDB:hasIDB,hasSW:hasSW,est:est});
            }catch(e){return JSON.stringify({hasLS:false,hasIDB:false,hasSW:false,est:0});}
        })();"""
    ) { json ->
        try {
            if (json == null) return@evaluateJavascript
            var s = json.trim()
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
            }
            val hasLS = s.contains("\"hasLS\":true")
            val hasIDB = s.contains("\"hasIDB\":true")
            val hasSW = s.contains("\"hasSW\":true")
            val est = Regex("\"est\":(\\d+)").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            vm.updateStorageFootprint(origin, cookieCount, hasLS, hasIDB, hasSW, est)
        } catch (_: Exception) {}
    }
}

/** Extracts a link URL from a long-press hit, or null when it wasn't on a link. */
internal fun linkUrlFromHitTest(hitType: Int?, extra: String?): String? = when (hitType) {
    WebView.HitTestResult.SRC_ANCHOR_TYPE,
    WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> extra?.takeIf { it.isNotBlank() }
    else -> null
}
