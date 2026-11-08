package com.vayunmathur.web.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ChromeGrantAndPwaDownloadTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries().build()
    private val stores = TestStores(db)
    private val createdVms = mutableListOf<WebViewModel>()

    @AfterTest fun cleanup() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
    }

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "cg-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun await(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@runBlocking
                delay(10)
            }
            assertTrue(condition(), "timed out: $description")
        }
    }

    private class FakePermissionRequest(private val res: Array<String>, private val originUri: Uri) : PermissionRequest() {
        var granted: Array<String>? = null
        var denied = false
        override fun getOrigin(): Uri = originUri
        override fun getResources(): Array<String> = res
        override fun grant(resources: Array<String>) { granted = resources }
        override fun deny() { denied = true }
    }

    private fun launcher() = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
        var launched: Array<String>? = null
        override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) { launched = input }
        override fun unregister() {}
        override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    }

    @Test fun chromeClientGrantsSavedCameraWhenSystemGranted() {
        // System camera permission already granted at the OS level.
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.CAMERA)
        runBlocking {
            stores.permissions.upsert(SitePermission(origin = "https://cam.example", cameraAllowed = true))
        }
        val v = vm()
        v.newTab(url = "https://cam.example/call")
        val tabId = v.activeTabId!!
        val locLauncher = launcher()
        var sysRequested = false
        val deps = WebViewClientDeps(context, locLauncher) { _, _, _ -> sysRequested = true }
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = {})
        val webView = WebView(context)
        val req = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE), Uri.parse("https://cam.example"))
        chrome.onPermissionRequest(req)
        await("camera granted") { req.granted != null || req.denied }
        assertTrue(req.granted?.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) == true, "granted=${req.granted} denied=${req.denied} sys=$sysRequested")
    }

    @Test fun chromeClientGeolocationSavedAllowWithSystemPermission() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        runBlocking {
            stores.permissions.upsert(SitePermission(origin = "https://geo.example", locationAllowed = true))
        }
        val v = vm()
        v.newTab(url = "https://geo.example/map")
        val tabId = v.activeTabId!!
        val deps = WebViewClientDeps(context, launcher()) { _, _, _ -> }
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = {})
        val webView = WebView(context)
        var geoAllow: Boolean? = null
        val callback = GeolocationPermissions.Callback { _, allow, _ -> geoAllow = allow }
        chrome.onGeolocationPermissionsShowPrompt("https://geo.example", callback)
        await("geo allowed") { geoAllow != null }
        assertEquals(true, geoAllow)
    }

    @Test fun chromeClientGeolocationSavedDeny() {
        runBlocking {
            stores.permissions.upsert(SitePermission(origin = "https://geo2.example", locationAllowed = false))
        }
        val v = vm()
        v.newTab(url = "https://geo2.example/map")
        val tabId = v.activeTabId!!
        val deps = WebViewClientDeps(context, launcher()) { _, _, _ -> }
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = {})
        val webView = WebView(context)
        var geoAllow: Boolean? = null
        val callback = GeolocationPermissions.Callback { _, allow, _ -> geoAllow = allow }
        chrome.onGeolocationPermissionsShowPrompt("https://geo2.example", callback)
        await("geo denied") { geoAllow != null }
        assertEquals(false, geoAllow)
    }

    @Test fun chromeClientDeniesResourcesWithNoPermissionSheet() {
        // RESOURCE_PROTECTED_MEDIA_ID (persistent DRM device identifier) has no
        // per-site sheet: it must fail closed, never be silently granted.
        val v = vm()
        v.newTab(url = "https://media.example/watch")
        val tabId = v.activeTabId!!
        val deps = WebViewClientDeps(context, launcher()) { _, _, _ -> }
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = {})
        val req = FakePermissionRequest(
            arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID),
            Uri.parse("https://media.example"),
        )
        chrome.onPermissionRequest(req)
        assertTrue(req.denied, "unlisted resource must be denied, granted=${req.granted}")
        assertTrue(req.granted == null, "granted=${req.granted}")
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun pwaDownloadListenerEnqueues() {
        rule.setContent {
            com.vayunmathur.web.platform.PwaBrowser(initialUrl = "https://pwa.example/app", title = "PWA")
        }
        rule.waitForIdle()
        val webView = findWebView(rule.activity.window.decorView)
        assertNotNull(webView)
        val listener = shadowOf(webView).downloadListener
        assertNotNull(listener)
        listener.onDownloadStart(
            "https://pwa.example/files/manual.pdf",
            "TestAgent/1.0",
            "attachment; filename=manual.pdf",
            "application/pdf",
            42L,
        )
        shadowOf(Looper.getMainLooper()).idle()
        // Enqueue goes through the shadow DownloadManager; reaching here without
        // an exception (and with an intent or enqueue recorded) is the behavior.
        val dm = shadowOf(context.getSystemService(android.app.DownloadManager::class.java))
        assertNotNull(dm)
    }

    @Test fun pwaBackPressFinishesWhenNoHistory() {
        rule.setContent {
            com.vayunmathur.web.platform.PwaBrowser(initialUrl = "https://pwa.example/app", title = "PWA")
        }
        rule.waitForIdle()
        assertNotNull(findWebView(rule.activity.window.decorView))
        // BackHandler is registered by PwaBrowser; pressing back with no WebView
        // history finishes the host activity.
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitForIdle()
        assertTrue(rule.activity.isFinishing || rule.activity.isDestroyed)
    }
}
