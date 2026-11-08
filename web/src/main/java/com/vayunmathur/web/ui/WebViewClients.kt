package com.vayunmathur.web.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.PwaHelper
import com.vayunmathur.web.platform.SitePermissionType
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.allowExternalRedirect
import com.vayunmathur.web.platform.noteNavigation
import com.vayunmathur.web.platform.onPwaInfoDetected
import com.vayunmathur.web.platform.onRequestBlocked
import com.vayunmathur.web.platform.onTabCanGoBack
import com.vayunmathur.web.platform.onTabCanGoForward
import com.vayunmathur.web.platform.onTabProgress
import com.vayunmathur.web.platform.onTabTitleChange
import com.vayunmathur.web.platform.onTabUrlChange
import com.vayunmathur.web.platform.recordHistoryVisit
import com.vayunmathur.web.platform.requestFileChooser
import com.vayunmathur.web.platform.requestGeolocation
import com.vayunmathur.web.platform.requestWebPermission
import com.vayunmathur.web.platform.resetBlockedCount
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.platform.shieldsFor

internal const val WEB_VIEW_BROWSER_TAG = "WebViewBrowser"

/** Callbacks the clients need that live in the composable (launchers + system-permission state). */
internal class WebViewClientDeps(
    val context: Context,
    val locationPermissionLauncher: ActivityResultLauncher<Array<String>>,
    val requestSystemPermission: (request: PermissionRequest, needsCamera: Boolean, needsMic: Boolean) -> Unit,
)

/**
 * The shields-aware WebViewClient: external-scheme routing, nav-state mirroring,
 * storage/PWA probes on finish, and visited-history updates.
 */
internal fun createBrowserWebViewClient(
    ctx: Context,
    tabId: String,
    viewModel: WebViewModel,
    isPrivateTab: Boolean,
): ShieldsWebViewClient = object : ShieldsWebViewClient(
    context = ctx,
    shieldsFor = { host -> viewModel.shieldsFor(host, isPrivateTab) },
    onBlocked = { _, _ -> viewModel.onRequestBlocked(tabId) },
    // Load the rewritten URL, not just mirror it: returning true above
    // cancels the original load, so without this the tab shows the old
    // page while the omnibox shows the new URL (reads as a scheme flip).
    onNavigate = { view, rewritten ->
        viewModel.onTabUrlChange(tabId, rewritten)
        view.loadUrl(rewritten)
    },
) {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return false
        if (scheme !in setOf("about", "data", "blob", "javascript") && !BrowserUtils.isWebScheme(scheme)) {
            // Swallowed rather than passed on when the tab is only being
            // restored: the page still renders, it just doesn't throw the
            // user back into the app they last left.
            if (!viewModel.allowExternalRedirect(tabId, request.hasGesture())) return true
            return com.vayunmathur.web.platform.openExternalUri(
                ctx,
                request.url.toString(),
            ) { fallback -> view.loadUrl(fallback) }
        }
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        viewModel.resetBlockedCount(tabId)
        url?.let {
            viewModel.onTabUrlChange(tabId, it)
            // Catch-all for the loads shouldOverrideUrlLoading never sees:
            // programmatic loadUrl, redirects and session restore.
            viewModel.noteNavigation(it)
        }
        viewModel.onTabCanGoBack(tabId, view.canGoBack())
        viewModel.onTabCanGoForward(tabId, view.canGoForward())
    }

    override fun onPageFinished(view: WebView, url: String?) {
        url?.let { viewModel.onTabUrlChange(tabId, it) }
        viewModel.onTabCanGoBack(tabId, view.canGoBack())
        viewModel.onTabCanGoForward(tabId, view.canGoForward())
        CookieManager.getInstance().flush()
        val title = view.title?.takeIf { it.isNotBlank() } ?: url ?: ""
        if (title.isNotBlank()) {
            viewModel.onTabTitleChange(tabId, title)
            viewModel.recordHistoryVisit(url ?: "", title, isPrivateTab)
        }
        url?.let { u ->
            if (BrowserUtils.isWebUrl(u)) {
                runCatching {
                    val origin = BrowserUtils.originFromUrl(u)
                    val cookies = CookieManager.getInstance().getCookie(u)
                    val cookieCount = cookies?.split(";")?.count { it.isNotBlank() } ?: 0
                    view.evalJsForStorageInfo(origin, cookieCount, viewModel)
                }.onFailure { e ->
                    Log.status(WEB_VIEW_BROWSER_TAG, "storage snapshot failed", e)
                }
                // PWA / Add-to-Home detection: probe for manifest + best icon + theme-color
                runCatching {
                    view.evaluateJavascript(PwaHelper.MANIFEST_PROBE_JS) { json ->
                        val info = PwaHelper.parseProbeJson(json)
                        if (info != null && info.origin.isNotBlank()) {
                            viewModel.onPwaInfoDetected(tabId, info)
                        }
                        // no need to keep raw json
                    }
                }.onFailure { e ->
                    Log.status(WEB_VIEW_BROWSER_TAG, "pwa probe failed", e)
                }
            }
        }
        viewModel.captureThumbnail(tabId, view)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        url?.let { viewModel.onTabUrlChange(tabId, it) }
        viewModel.onTabCanGoBack(tabId, view.canGoBack())
        viewModel.onTabCanGoForward(tabId, view.canGoForward())
    }
}

/**
 * The WebChromeClient: progress/title/icon, geolocation + media permission
 * delegation, file chooser, and popup-window routing into new tabs.
 */
internal fun createBrowserWebChromeClient(
    tabId: String,
    viewModel: WebViewModel,
    isPrivateTab: Boolean,
    deps: WebViewClientDeps,
    onRequestNewTab: (String) -> Unit,
    fullscreenHost: WebFullscreenHost? = null,
): WebChromeClient = object : WebChromeClient() {
    override fun onProgressChanged(view: WebView, newProgress: Int) {
        viewModel.onTabProgress(tabId, newProgress / 100f)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!title.isNullOrBlank()) viewModel.onTabTitleChange(tabId, title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        val url = view.url ?: return
        if (icon != null) FaviconStore.put(url, icon, isPrivateTab)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback
    ) {
        // Private tabs: deny location without persisting
        if (viewModel.tabs.find { it.id == tabId }?.isPrivate == true) {
            callback.invoke(origin, false, false)
            return
        }

        viewModel.requestGeolocation(
            origin = origin,
            onAllow = {
                val hasFine = ContextCompat.checkSelfPermission(deps.context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasCoarse = ContextCompat.checkSelfPermission(deps.context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasFine && !hasCoarse) {
                    // Defer until system permission granted; keep geolocation callback pending via VM state
                    // We invoke deny for now and re-prompt after system permission result via launcher which will call grantGeolocation again on next site request.
                    // Better: hold callback in local and request system perms now, then invoke on result.
                    deps.locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                    // We must retain the callback somewhere to invoke after permission result.
                    // Store in tag of WebView temporarily — use a holder map keyed by origin.
                    // Simplest: deny now and let site re-request which will then auto-grant because system perm will be granted and saved permission says allowed.
                    callback.invoke(origin, false, false)
                } else {
                    callback.invoke(origin, true, false)
                }
            },
            onDeny = { callback.invoke(origin, false, false) }
        )
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val origin = request.origin.toString()
        if (viewModel.tabs.find { it.id == tabId }?.isPrivate == true) {
            request.deny()
            return
        }

        val resources = request.resources
        val types = mutableListOf<SitePermissionType>()
        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) types.add(SitePermissionType.CAMERA)
        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) types.add(SitePermissionType.MICROPHONE)

        if (types.isEmpty()) {
            request.grant(resources)
            return
        }

        viewModel.requestWebPermission(
            origin = origin,
            types = types,
            grant = { grantedTypes ->
                val needsCamera = SitePermissionType.CAMERA in grantedTypes
                val needsMic = SitePermissionType.MICROPHONE in grantedTypes
                val hasCamera = if (needsCamera) {
                    ContextCompat.checkSelfPermission(deps.context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                } else true
                val hasMic = if (needsMic) {
                    ContextCompat.checkSelfPermission(deps.context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                } else true

                if (!hasCamera || !hasMic) {
                    deps.requestSystemPermission(request, needsCamera, needsMic)
                    return@requestWebPermission
                }

                val toGrant = mutableListOf<String>()
                if (SitePermissionType.CAMERA in grantedTypes && resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                    toGrant.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                }
                if (SitePermissionType.MICROPHONE in grantedTypes && resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    toGrant.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                }
                if (toGrant.isNotEmpty()) request.grant(toGrant.toTypedArray()) else request.deny()
            },
            deny = { request.deny() }
        )
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        viewModel.requestFileChooser(filePathCallback, fileChooserParams)
        return true
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        fullscreenHost?.onShowCustomView(view, callback) ?: super.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        if (fullscreenHost?.onHideCustomView() != true) super.onHideCustomView()
    }

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val newWebView = WebView(view.context).apply {
            settings.javaScriptEnabled = viewModel.jsEnabled
            settings.domStorageEnabled = true
            settings.applySystemDarkMode()
        }
        newWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                onRequestNewTab(req.url.toString())
                return true
            }
            override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                url?.let { onRequestNewTab(it) }
                v.stopLoading()
            }
        }
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }
}
