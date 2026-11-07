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

internal fun applyWebViewSettings(webView: WebView, viewModel: WebViewModel) {
    val settings = webView.settings

    settings.javaScriptEnabled = viewModel.jsEnabled
    settings.javaScriptCanOpenWindowsAutomatically = viewModel.jsEnabled

    // DOM storage = localStorage / sessionStorage, persisted in WebView data dir
    settings.domStorageEnabled = true
    settings.databaseEnabled = true

    // HTTP cache — user selectable for speed / offline
    settings.cacheMode = viewModel.cacheMode.webSettingsValue

    settings.allowFileAccess = true
    settings.allowContentAccess = true

    // Offscreen pre-raster speeds first paint
    settings.offscreenPreRaster = true

    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true

    // Aggressive shields refuse plaintext subresources outright; otherwise stay permissive
    // so pages with a few http:// images still render.
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
        WebSettings.getDefaultUserAgent(webView.context)
    }

    settings.mediaPlaybackRequiresUserGesture = false

    settings.applySystemDarkMode()

    // Safe browsing
    try {
        val compat = Class.forName("androidx.webkit.WebSettingsCompat")
        val feature = Class.forName("androidx.webkit.WebViewFeature")
        val isSupported = feature.getMethod("isFeatureSupported", String::class.java).invoke(null, "SAFE_BROWSING_ENABLE") as Boolean
        if (isSupported) {
            compat.getMethod("setSafeBrowsingEnabled", WebSettings::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, settings, true)
        }
    } catch (_: Exception) {}
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
