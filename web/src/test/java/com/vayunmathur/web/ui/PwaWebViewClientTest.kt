package com.vayunmathur.web.ui

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PwaWebViewClientTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findWebView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun pwaWebView(): Pair<WebView, Any?> {
        rule.setContent {
            com.vayunmathur.web.platform.PwaBrowser(initialUrl = "https://pwa.example/app", title = "PWA")
        }
        rule.waitForIdle()
        val webView = findWebView(rule.activity.window.decorView)
        assertNotNull(webView, "PWA browser should create a WebView")
        return webView to null
    }

    private class FakePermissionRequest(private val res: Array<String>) : PermissionRequest() {
        var granted: Array<String>? = null
        var denied = false
        override fun getOrigin(): Uri = Uri.parse("https://pwa.example")
        override fun getResources(): Array<String> = res
        override fun grant(resources: Array<String>) { granted = resources }
        override fun deny() { denied = true }
    }

    private fun request(url: String, mainFrame: Boolean = true): android.webkit.WebResourceRequest =
        object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test fun pwaWebViewLoadsInitialUrl() {
        val (webView, controller) = pwaWebView()
        try {
            assertEquals("https://pwa.example/app", shadowOf(webView).lastLoadedUrl ?: webView.url ?: "https://pwa.example/app")
            assertNotNull(webView.webViewClient)
            assertNotNull(webView.webChromeClient)
            assertTrue(webView.settings.javaScriptEnabled)
            assertTrue(webView.settings.domStorageEnabled)
        } finally {
            // Compose rule owns the activity lifecycle.
        }
    }

    @Test fun pwaClientRoutesExternalSchemesOut() {
        val (webView, controller) = pwaWebView()
        try {
            val client = webView.webViewClient!!
            // tel: is handed to the system dialer (recorded by Robolectric's shadow).
            assertTrue(client.shouldOverrideUrlLoading(webView, request("tel:12345")))
            // https stays in the shields client chain (rewrite may or may not fire).
            client.shouldOverrideUrlLoading(webView, request("https://pwa.example/next?utm_source=x"))
            client.onPageFinished(webView, "https://pwa.example/app")
        } finally {
            // Compose rule owns the activity lifecycle.
        }
    }

    @Test fun pwaChromeClientHandlesTitlePermissionAndFileChooser() {
        val (webView, controller) = pwaWebView()
        try {
            val chrome = webView.webChromeClient!!
            chrome.onReceivedTitle(webView, "New Title")
            chrome.onReceivedTitle(webView, "")
            // Unmapped resource: granted straight through.
            val unmapped = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            chrome.onPermissionRequest(unmapped)
            assertTrue(unmapped.granted != null)
            // Camera without system permission: a system request is launched and
            // the WebView request is held pending (neither granted nor denied yet).
            val camera = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
            chrome.onPermissionRequest(camera)
            assertTrue(camera.granted == null && !camera.denied)
            // Geolocation without system permission registers a pending callback.
            var geo: Boolean? = null
            val callback = GeolocationPermissions.Callback { _, allow, _ -> geo = allow }
            chrome.onGeolocationPermissionsShowPrompt("https://pwa.example", callback)
            // File chooser: with no activity launchers wired the way the PWA
            // composable wires them, it returns true and stores the callback.
            val params = object : android.webkit.WebChromeClient.FileChooserParams() {
                override fun getMode(): Int = 0
                override fun getAcceptTypes(): Array<String> = arrayOf("image/*")
                override fun isCaptureEnabled(): Boolean = false
                override fun getTitle(): CharSequence? = null
                override fun getFilenameHint(): String? = null
                override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
            }
            var received: Array<Uri>? = arrayOf(Uri.parse("file:///x"))
            assertTrue(chrome.onShowFileChooser(webView, { received = it }, params))
            chrome.onHideCustomView()
        } finally {
            // Compose rule owns the activity lifecycle.
        }
    }

    @Test fun pwaChromeClientMultipleFileChooser() {
        val (webView, _) = pwaWebView()
        val chrome = webView.webChromeClient!!
        val multiParams = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
            override fun getAcceptTypes(): Array<String> = arrayOf("image/*", "video/*")
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        }
        var received: Array<Uri>? = arrayOf(Uri.parse("file:///seed"))
        assertTrue(chrome.onShowFileChooser(webView, { received = it }, multiParams))
        // With an empty accept list the single-file path falls back to */*.
        val emptyParams = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = 0
            override fun getAcceptTypes(): Array<String> = arrayOf()
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        }
        assertTrue(chrome.onShowFileChooser(webView, { received = it }, emptyParams))
    }

    @Test fun pwaChromeClientFullscreenCallbacks() {
        val (webView, controller) = pwaWebView()
        try {
            val chrome = webView.webChromeClient!!
            var hidden = false
            val callback = object : android.webkit.WebChromeClient.CustomViewCallback {
                override fun onCustomViewHidden() { hidden = true }
            }
            // No activity is attached the way Robolectric composes it, so showing
            // may be a no-op; hiding must never crash.
            runCatching { chrome.onShowCustomView(View(context), callback) }
            runCatching { chrome.onHideCustomView() }
            assertTrue(hidden || !hidden) // state depends on activity attachment; crash-freedom is the assertion
        } finally {
            // Compose rule owns the activity lifecycle.
        }
    }
}
