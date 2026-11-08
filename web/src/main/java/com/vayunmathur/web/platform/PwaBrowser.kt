package com.vayunmathur.web.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.openAppSettings
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.web.R
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.shields.FarblingConfig
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.ui.LinkContextMenu
import com.vayunmathur.web.ui.WebFullscreenHost
import com.vayunmathur.web.ui.applySystemDarkMode
import com.vayunmathur.web.ui.linkUrlFromHitTest

/**
 * Mutable wiring for the PWA WebView clients. Lives outside composition so the
 * client builders stay plain functions; the composable assigns every field.
 */
private class PwaChromeDeps(
    val context: Context,
    val fullscreenHost: WebFullscreenHost,
) {
    var onTitle: (String) -> Unit = {}
    var webViewRef: WebView? = null
    var pendingSysPermissionRequest: PermissionRequest? = null
    var pendingGeoCallback: Pair<String, GeolocationPermissions.Callback>? = null
    var cameraPermissionRequest: () -> Unit = {}
    var micPermissionRequest: () -> Unit = {}
    var cameraMicPermissionRequest: () -> Unit = {}
    var locationLauncher: ActivityResultLauncher<Array<String>>? = null
    var multiDocLauncher: ActivityResultLauncher<Array<String>>? = null
    var singleDocLauncher: ActivityResultLauncher<Array<String>>? = null
}

private class SysPermissionLaunchers(
    val camera: () -> Unit,
    val mic: () -> Unit,
    val cameraMic: () -> Unit,
)

@Composable
internal fun PwaBrowser(
    initialUrl: String,
    title: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val deps = remember(context) {
        PwaChromeDeps(context, WebFullscreenHost(context))
    }

    var currentTitle by remember { mutableStateOf(title ?: "") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var linkMenuUrl by remember { mutableStateOf<String?>(null) }
    deps.onTitle = { currentTitle = it }
    deps.webViewRef = webViewRef

    val sysLaunchers = rememberSysPermissionLaunchers { granted ->
        resolveSysPermissionRequest(deps.pendingSysPermissionRequest, granted)
        deps.pendingSysPermissionRequest = null
    }
    deps.cameraPermissionRequest = sysLaunchers.camera
    deps.micPermissionRequest = sysLaunchers.mic
    deps.cameraMicPermissionRequest = sysLaunchers.cameraMic
    deps.locationLauncher = rememberPwaLocationLauncher(activity, context, deps)
    val (multiDocLauncher, singleDocLauncher) = rememberPwaFileLaunchers { webViewRef }
    deps.multiDocLauncher = multiDocLauncher
    deps.singleDocLauncher = singleDocLauncher

    PwaBackHandler(activity, deps) { webViewRef }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createPwaWebView(ctx, initialUrl, deps) { url -> linkMenuUrl = url }
                    .also { webViewRef = it }
            },
            update = { wv ->
                webViewRef = wv
                deps.webViewRef = wv
                if ((wv.url ?: "") != initialUrl && wv.url.isNullOrBlank()) {
                    wv.loadUrl(initialUrl)
                }
            },
        )

        linkMenuUrl?.let { linkUrl ->
            PwaLinkMenu(
                context = context,
                linkUrl = linkUrl,
                onDismiss = { linkMenuUrl = null },
                onOpenLink = { webViewRef?.loadUrl(linkUrl) },
            )
        }
    }
}

@Composable
private fun PwaBackHandler(
    activity: ComponentActivity?,
    deps: PwaChromeDeps,
    getWebView: () -> WebView?,
) {
    BackHandler {
        val wv = getWebView()
        when {
            deps.fullscreenHost.isFullscreen -> deps.fullscreenHost.onHideCustomView()
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> activity?.finish()
        }
    }
}

@Composable
private fun PwaLinkMenu(
    context: Context,
    linkUrl: String,
    onDismiss: () -> Unit,
    onOpenLink: () -> Unit,
) {
    LinkContextMenu(
        url = linkUrl,
        onDismiss = onDismiss,
        onCopyLink = {
            ExternalIntents.copyToClipboard(context, linkUrl, linkUrl)
            AppMessages.show(context.getString(R.string.link_copied))
        },
        onShareLink = {
            ExternalIntents.shareText(context, linkUrl, context.getString(R.string.share_link))
        },
        onOpenLink = onOpenLink,
    )
}

/**
 * Camera/mic launchers sharing one result handler, which drives the pending
 * WebView permission request's grant()/deny().
 */
@Composable
private fun rememberSysPermissionLaunchers(onResult: (Boolean) -> Unit): SysPermissionLaunchers {
    val camera = rememberPermissionRequest(Manifest.permission.CAMERA, onResult)
    val mic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO, onResult)
    val cameraMic = rememberMultiplePermissionRequest(
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
        onResult,
    )
    return SysPermissionLaunchers(camera, mic, cameraMic)
}

private fun resolveSysPermissionRequest(request: PermissionRequest?, granted: Boolean) {
    if (granted) {
        request?.grant(request.resources ?: emptyArray())
    } else {
        request?.deny()
    }
}

// Geolocation grants on EITHER fine OR coarse, so this keeps the raw multi-permission
// launcher (the shared helper reports all-granted, which would wrongly require both).
// Open app settings ONLY when BOTH are permanently denied — if either is still
// grantable the user isn't blocked.
@Composable
private fun rememberPwaLocationLauncher(
    activity: ComponentActivity?,
    context: Context,
    deps: PwaChromeDeps,
): ActivityResultLauncher<Array<String>> = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions(),
) { result ->
    val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
        result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    val pair = deps.pendingGeoCallback
    if (granted) {
        pair?.second?.invoke(pair.first, true, false)
    } else {
        pair?.second?.invoke(pair.first, false, false)
        openSettingsIfBothPermanentlyDenied(activity, context, result)
    }
    deps.pendingGeoCallback = null
}

private fun openSettingsIfBothPermanentlyDenied(
    activity: ComponentActivity?,
    context: Context,
    result: Map<String, Boolean>,
) {
    val bothPermanentlyDenied = result.all { (perm, granted) ->
        !granted && (activity == null ||
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm))
    }
    if (bothPermanentlyDenied) openAppSettings(context)
}

@Composable
private fun rememberPwaFileLaunchers(
    getWebView: () -> WebView?,
): Pair<ActivityResultLauncher<Array<String>>, ActivityResultLauncher<Array<String>>> {
    val multiDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        deliverFileResult(getWebView(), uris.toTypedArray().takeIf { it.isNotEmpty() })
    }
    val singleDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        deliverFileResult(getWebView(), uri?.let { arrayOf(it) })
    }
    return multiDocLauncher to singleDocLauncher
}

private fun deliverFileResult(webView: WebView?, value: Array<Uri>?) {
    @Suppress("UNCHECKED_CAST")
    (webView?.tag as? ValueCallback<Array<Uri>>)?.onReceiveValue(value)
    webView?.tag = null
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPwaWebView(
    ctx: Context,
    initialUrl: String,
    deps: PwaChromeDeps,
    onLongPressLink: (String) -> Unit,
): WebView = WebView(ctx).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
    val cookieManager = CookieManager.getInstance()
    cookieManager.setAcceptCookie(true)
    cookieManager.setAcceptThirdPartyCookies(this, shouldAcceptPwaThirdPartyCookies(ctx))

    applyPwaWebSettings(this, ctx)

    // Long-press on a link: mirror WebViewBrowser's hit-test pattern.
    setOnLongClickListener {
        val hit = hitTestResult
        val url = linkUrlFromHitTest(hit?.type, hit?.extra)
        if (url != null) {
            onLongPressLink(url)
            return@setOnLongClickListener true
        }
        false
    }
    runCatching { enableSafeBrowsing(settings) }

    setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        enqueuePwaDownload(ctx, url, userAgent, contentDisposition, mimeType)
    })

    webViewClient = createPwaWebViewClient(ctx, deps.onTitle)
    webChromeClient = createPwaWebChromeClient(deps)

    // Before the first load: document-start scripts do not apply retroactively.
    (webViewClient as ShieldsWebViewClient)
        .installFarbling(this, FarblingConfig.of(ShieldsSettings.AGGRESSIVE_DEFAULTS, emptyMap()))
    loadUrl(initialUrl)
}

/**
 * Whether the PWA WebView accepts third-party cookies. Browser tabs gate this on the
 * global block-third-party setting; the PWA window must honour the same pref instead of
 * hardcoding acceptance.
 */
internal fun shouldAcceptPwaThirdPartyCookies(context: Context): Boolean {
    val blocked = context
        .getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
        .getBoolean(P_BLOCK_THIRD_PARTY, false)
    return !blocked
}

private fun applyPwaWebSettings(webView: WebView, ctx: Context) {
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.domStorageEnabled = true
    settings.databaseEnabled = true
    settings.cacheMode = WebSettings.LOAD_DEFAULT
    settings.allowFileAccess = true
    settings.allowContentAccess = true
    settings.offscreenPreRaster = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
    // Installed sites always run the aggressive shields preset (see createPwaWebViewClient),
    // which resolves httpsOnly=true: refuse plaintext subresources like the browser tab does
    // instead of the permissive default.
    val pwaShields = EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS)
    settings.mixedContentMode = if (pwaShields.httpsOnly) {
        WebSettings.MIXED_CONTENT_NEVER_ALLOW
    } else {
        WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    settings.setGeolocationEnabled(true)
    @Suppress("DEPRECATION")
    runCatching {
        settings.setGeolocationDatabasePath(ctx.filesDir.absolutePath)
    }
    settings.setSupportMultipleWindows(true)
    settings.mediaPlaybackRequiresUserGesture = false
    settings.applySystemDarkMode()
}

private fun enqueuePwaDownload(
    ctx: Context,
    url: String,
    userAgent: String,
    contentDisposition: String?,
    mimeType: String,
) {
    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
    runCatching {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setMimeType(mimeType)
            addRequestHeader("User-Agent", userAgent)
            setDescription("Downloading $fileName")
            setTitle(fileName)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }
        dm.enqueue(request)
    }.onFailure {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}

private fun createPwaWebViewClient(
    ctx: Context,
    onTitle: (String) -> Unit,
): ShieldsWebViewClient = object : ShieldsWebViewClient(
    context = ctx,
    // Installed sites have no shields panel, so they always run the
    // aggressive preset the browser ships with.
    shieldsFor = { EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS) },
) {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return false
        if (scheme !in setOf("about", "data", "blob", "javascript") && !BrowserUtils.isWebScheme(scheme)) {
            return openExternalUri(ctx, request.url.toString()) { fallback -> view.loadUrl(fallback) }
        }
        // For PWA: stay inside same origin; external origins still load but that is okay.
        return super.shouldOverrideUrlLoading(view, request)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        CookieManager.getInstance().flush()
        val t = view.title?.takeIf { it.isNotBlank() } ?: url ?: ""
        if (t.isNotBlank()) onTitle(t)
    }
}

private fun createPwaWebChromeClient(deps: PwaChromeDeps): WebChromeClient = object : WebChromeClient() {
    override fun onReceivedTitle(view: WebView, t: String?) {
        if (!t.isNullOrBlank()) deps.onTitle(t)
    }

    override fun onShowCustomView(view: android.view.View, callback: CustomViewCallback) {
        deps.fullscreenHost.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() {
        if (!deps.fullscreenHost.onHideCustomView()) super.onHideCustomView()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        handlePwaGeolocationPrompt(deps, origin, callback)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        handlePwaPermissionRequest(deps, request)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        deps.webViewRef?.tag = filePathCallback
        val mimeTypes = fileChooserParams.safeAcceptTypes()
        runCatching {
            if (fileChooserParams.isMultipleSelection()) {
                deps.multiDocLauncher?.launch(mimeTypes.toLaunchArray())
            } else {
                val mt = mimeTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                deps.singleDocLauncher?.launch(arrayOf(mt))
            }
        }.onFailure {
            filePathCallback.onReceiveValue(null)
            deps.webViewRef?.tag = null
        }
        return true
    }
}

private fun handlePwaGeolocationPrompt(
    deps: PwaChromeDeps,
    origin: String,
    callback: GeolocationPermissions.Callback,
) {
    if (hasLocationPermission(deps.context)) {
        callback.invoke(origin, true, false)
        return
    }
    deps.pendingGeoCallback = origin to callback
    deps.locationLauncher?.launch(
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    )
}

private fun handlePwaPermissionRequest(deps: PwaChromeDeps, request: PermissionRequest) {
    val resources = request.resources
    val needsCamera = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
    val needsMic = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
    if (!needsCamera && !needsMic) {
        // No per-site sheet exists for these resources (MIDI, DRM device id, ...):
        // fail closed rather than silently granting, mirroring the browser tab.
        request.deny()
        return
    }
    if (hasMediaPermissions(deps.context, needsCamera, needsMic)) {
        request.grant(resources)
        return
    }
    deps.pendingSysPermissionRequest = request
    when {
        needsCamera && needsMic -> deps.cameraMicPermissionRequest()
        needsCamera -> deps.cameraPermissionRequest()
        else -> deps.micPermissionRequest()
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}

private fun hasMediaPermissions(context: Context, needsCamera: Boolean, needsMic: Boolean): Boolean {
    val cameraOk = !needsCamera ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val micOk = !needsMic ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    return cameraOk && micOk
}

/**
 * Enables Safe Browsing through AndroidX WebKit when the device supports it.
 * Reflection keeps the call working on WebView versions that predate the API.
 */
private fun enableSafeBrowsing(settings: WebSettings) {
    val feature = Class.forName("androidx.webkit.WebViewFeature")
    val isSupported = feature.getMethod("isFeatureSupported", String::class.java)
        .invoke(null, "SAFE_BROWSING_ENABLE") as Boolean
    if (!isSupported) return
    val compat = Class.forName("androidx.webkit.WebSettingsCompat")
    compat.getMethod(
        "setSafeBrowsingEnabled",
        WebSettings::class.java,
        Boolean::class.javaPrimitiveType,
    ).invoke(null, settings, true)
}
