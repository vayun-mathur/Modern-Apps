package com.vayunmathur.web.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.findActivity
import com.vayunmathur.library.ui.openAppSettings
import com.vayunmathur.library.ui.rememberMultiplePermissionRequest
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.web.platform.shields.FarblingConfig
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.addDownload
import com.vayunmathur.web.platform.denyGeolocation
import com.vayunmathur.web.platform.getCurrentUrl
import com.vayunmathur.web.platform.grantGeolocation

/**
 * Core WebView with:
 * - permission delegation: camera, mic, location, file chooser
 * - caching: HTTP cache (cacheMode), DOM storage (localStorage), database, offscreen preraster, geolocation DB
 * - cookies + localStorage/IndexedDB/SW tracked via JS probe + CookieManager, persisted in WebView profile dir + Room (StorageInfo)
 *
 * The clients live in [WebViewClients.kt] and settings in [WebViewSettings.kt];
 * this is the pool/factory/update shell plus the permission-launcher wiring.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewBrowser(
    tabId: String,
    initialUrl: String,
    viewModel: WebViewModel,
    modifier: Modifier = Modifier,
    onRequestNewTab: (String) -> Unit = {},
    webViewPool: MutableMap<String, WebView>,
    fullscreenHost: WebFullscreenHost? = null,
    onLinkLongPress: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val holder = remember(tabId) { WebViewHolder() }

    // Observed so a shields change recomposes and re-registers the document-start script;
    // the view model's own mirror is a plain map and would not trigger anything.
    val siteShields by viewModel.shieldSettings.collectAsStateWithLifecycle()
    val farblingConfig = remember(siteShields, viewModel.shields) {
        FarblingConfig.of(viewModel.shields, siteShields.associate { it.host to it.toSettings() })
    }

    var pendingSysPermissionRequest by remember { mutableStateOf<PermissionRequest?>(null) }

    fun resolveSysPermission(request: PermissionRequest?, granted: Boolean) {
        if (granted) {
            request?.grant(request.resources ?: emptyArray())
        } else {
            request?.deny()
        }
    }

    val cameraPermissionRequest = rememberPermissionRequest(
        Manifest.permission.CAMERA
    ) { granted ->
        resolveSysPermission(pendingSysPermissionRequest, granted)
        pendingSysPermissionRequest = null
    }

    val micPermissionRequest = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO
    ) { granted ->
        resolveSysPermission(pendingSysPermissionRequest, granted)
        pendingSysPermissionRequest = null
    }

    val cameraMicPermissionRequest = rememberMultiplePermissionRequest(
        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    ) { allGranted ->
        resolveSysPermission(pendingSysPermissionRequest, allGranted)
        pendingSysPermissionRequest = null
    }

    // Geolocation grants on EITHER fine OR coarse, so this keeps the raw multi-permission
    // launcher (the shared helper reports all-granted, which would wrongly require both).
    // Open app settings ONLY when BOTH are permanently denied — if either is still
    // grantable the user isn't blocked.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            // grant via stored geolocation callback in ViewModel
            viewModel.pendingGeolocationPrompt?.let {
                viewModel.grantGeolocation()
            }
        } else {
            viewModel.denyGeolocation()
            val activity = context.findActivity()
            val bothPermanentlyDenied = result.all { (perm, isGranted) ->
                !isGranted && (activity == null ||
                    !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm))
            }
            if (bothPermanentlyDenied) openAppSettings(context)
        }
    }

    // The tab a WebView belongs to never changes, so capture privacy once instead
    // of reading the Compose tab list from the render thread.
    val isPrivateTab = remember(tabId, viewModel.incognito) {
        viewModel.incognito || viewModel.tabs.find { it.id == tabId }?.isPrivate == true
    }
    val clientDeps = remember(context, locationPermissionLauncher) {
        WebViewClientDeps(
            context = context,
            locationPermissionLauncher = locationPermissionLauncher,
            requestSystemPermission = { request, needsCamera, needsMic ->
                pendingSysPermissionRequest = request
                when {
                    needsCamera && needsMic -> cameraMicPermissionRequest()
                    needsCamera -> cameraPermissionRequest()
                    needsMic -> micPermissionRequest()
                }
            },
        )
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            webViewPool[tabId]?.let { existing ->
                (existing.parent as? ViewGroup)?.removeView(existing)
                applyWebViewSettings(existing, viewModel)
                existing.setOnLongClickListener {
                    val hit = existing.hitTestResult
                    val url = linkUrlFromHitTest(hit?.type, hit?.extra)
                    if (url != null) {
                        onLinkLongPress(url)
                        return@setOnLongClickListener true
                    }
                    false
                }
                return@AndroidView existing
            }

            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, !viewModel.blockThirdPartyCookies)

                applyWebViewSettings(this, viewModel)

                // Long-press on a link: intercept before WebView's default tooltip/context menu
                setOnLongClickListener {
                    val hit = hitTestResult
                    val url = linkUrlFromHitTest(hit?.type, hit?.extra)
                    if (url != null) {
                        onLinkLongPress(url)
                        return@setOnLongClickListener true
                    }
                    false
                }

                setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                    Log.dev(WEB_VIEW_BROWSER_TAG, "Download: $fileName $url")
                    viewModel.addDownload(url, fileName, mimeType, contentLength)
                    runCatching {
                        val dm = ctx.getSystemService(android.app.DownloadManager::class.java)
                        val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
                            setMimeType(mimeType)
                            addRequestHeader("User-Agent", userAgent)
                            setDescription("Downloading $fileName")
                            setTitle(fileName)
                            setNotificationVisibility(
                                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                            )
                            setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS,
                                fileName,
                            )
                        }
                        dm.enqueue(request)
                    }.onFailure { e ->
                        Log.error(WEB_VIEW_BROWSER_TAG, "Download enqueue failed", e)
                        runCatching {
                            val fallback = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                Uri.parse(url),
                            ).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(fallback)
                        }
                    }
                })

                webViewClient = createBrowserWebViewClient(ctx, tabId, viewModel, isPrivateTab)
                webChromeClient = createBrowserWebChromeClient(tabId, viewModel, isPrivateTab, clientDeps, onRequestNewTab, fullscreenHost)

                val toLoad = if (initialUrl.isBlank()) "about:blank" else initialUrl
                // Before the first load: document-start scripts do not apply retroactively.
                (webViewClient as ShieldsWebViewClient).installFarbling(this, farblingConfig)
                // Fail closed: a non-web initial URL (e.g. a javascript: string mirrored from a
                // server-supplied result or a stale tab row) must never execute in this pooled
                // WebView's origin. Park on a blank page instead of loading it.
                loadUrl(if (BrowserUtils.canLoadInWebView(toLoad)) toLoad else "about:blank")
            }.also {
                webViewPool[tabId] = it
                applyWebViewSettings(it, viewModel)
            }
        },
        update = { webView ->
            val current = webView.url ?: ""
            val desired = viewModel.getCurrentUrl(tabId)
            // canLoadInWebView fails closed: server- or page-supplied non-web strings mirrored
            // into tab state (search results, history rows, long-press links) are never handed
            // to loadUrl, where javascript: would execute in this WebView's stale origin.
            if (desired.isNotBlank() && desired != current && !viewModel.omniboxFocused &&
                BrowserUtils.canLoadInWebView(desired)) {
                if (viewModel.activeTabId == tabId) {
                    // Always load when desired differs — the previous prog >= 1f guard prevented
                    // external intents from loading while the current page was still loading,
                    // causing topbar/content mismatch.
                    webView.loadUrl(desired)
                }
            }
            applyWebViewSettings(webView, viewModel)
            (webView.webViewClient as? ShieldsWebViewClient)
                ?.installFarbling(webView, farblingConfig)
            // Keep the long-press handler in sync after pool reuse / recomposition
            webView.setOnLongClickListener {
                val hit = webView.hitTestResult
                val url = linkUrlFromHitTest(hit?.type, hit?.extra)
                if (url != null) {
                    onLinkLongPress(url)
                    return@setOnLongClickListener true
                }
                false
            }
            holder.webView = webView
        }
    )

    DisposableEffect(tabId) { onDispose { } }
}

private class WebViewHolder { var webView: WebView? = null }
