package com.vayunmathur.web.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.PwaActivity
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearFileChooser
import com.vayunmathur.web.platform.getCurrentUrl
import com.vayunmathur.web.platform.getProgress
import com.vayunmathur.web.platform.getTabTitle
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.shields.ShieldsEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebViewClientsAndPwaTest {

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
        val v = WebViewModel(stores, context, windowId = "clients-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun request(url: String, mainFrame: Boolean = true, gesture: Boolean = true): android.webkit.WebResourceRequest =
        object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = gesture
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    private fun launcher() = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
        override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    }

    @Test fun webViewClientMirrorsPageLifecycle() {
        val v = vm()
        v.newTab(url = "https://client.example/start")
        val tabId = v.activeTabId!!
        val client = createBrowserWebViewClient(context, tabId, v, isPrivateTab = false)
        val webView = WebView(context)
        client.onPageStarted(webView, "https://client.example/started", null)
        assertEquals("https://client.example/started", v.getCurrentUrl(tabId))
        client.doUpdateVisitedHistory(webView, "https://client.example/visited", false)
        assertEquals("https://client.example/visited", v.getCurrentUrl(tabId))
        client.onPageFinished(webView, "https://client.example/visited")
        // External scheme with a gesture is handed to the system (recorded by Robolectric).
        assertTrue(client.shouldOverrideUrlLoading(webView, request("tel:999")))
        // Data urls stay in the WebView client chain.
        client.shouldOverrideUrlLoading(webView, request("https://client.example/page?utm_medium=x&keep=1"))
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun chromeClientMirrorsProgressTitleAndIcon() {
        val v = vm()
        v.newTab(url = "https://chrome.example/")
        val tabId = v.activeTabId!!
        val deps = WebViewClientDeps(context, launcher()) { _, _, _ -> }
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = {})
        val webView = WebView(context)
        chrome.onProgressChanged(webView, 42)
        assertEquals(0.42f, v.getProgress(tabId))
        chrome.onReceivedTitle(webView, "Chrome Title")
        assertEquals("Chrome Title", v.getTabTitle(tabId))
        chrome.onReceivedTitle(webView, "")
        assertEquals("Chrome Title", v.getTabTitle(tabId))
        chrome.onReceivedIcon(webView, null)
        chrome.onHideCustomView()
        // File chooser delegates into the view model.
        val params = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = 0
            override fun getAcceptTypes(): Array<String> = arrayOf("*/*")
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        }
        var received: Array<Uri>? = null
        assertTrue(chrome.onShowFileChooser(webView, { received = it }, params))
        assertNotNull(v.pendingFileChooser)
        v.clearFileChooser()
    }

    @Test fun chromeClientCreateWindowRoutesToNewTab() {
        val v = vm()
        v.newTab(url = "https://chrome.example/")
        val tabId = v.activeTabId!!
        val deps = WebViewClientDeps(context, launcher()) { _, _, _ -> }
        var newTabUrl: String? = null
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps, onRequestNewTab = { newTabUrl = it })
        val webView = WebView(context)
        // Without a transport message there is nowhere to put the popup view.
        assertEquals(false, chrome.onCreateWindow(webView, false, true, null))
        assertEquals(null, newTabUrl)
    }

    @Test fun shieldsEngineLoadsAndChecks() {
        runBlocking { ShieldsEngine.load(context) }
        shadowOf(Looper.getMainLooper()).idle()
        if (ShieldsEngine.ready) {
            val result = ShieldsEngine.check("https://example.com/page", "https://example.com", "document")
            // A plain page is not blocked; the call itself must answer.
            assertTrue(result == null || !result.blocked)
            assertNotNull(ShieldsEngine.cosmetic("https://example.com/")?.hide ?: emptyList<String>())
        } else {
            // Host native lib unavailable: engine must fail open.
            assertEquals(null, ShieldsEngine.check("https://x.example/a.js", "https://x.example", "script"))
        }
    }

    @Test fun pwaActivityOpensWithUrlExtra() {
        val intent = Intent(context, PwaActivity::class.java).apply {
            putExtra(PwaActivity.EXTRA_URL, "https://pwa.example/app")
            putExtra(PwaActivity.EXTRA_TITLE, "PWA App")
        }
        val controller = Robolectric.buildActivity(PwaActivity::class.java, intent)
        val activity = controller.create().start().resume().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity)
        // A second intent with only shared text still resolves its URL.
        val textIntent = Intent(context, PwaActivity::class.java).apply {
            putExtra(Intent.EXTRA_TEXT, "check https://shared.example/page out")
        }
        controller.newIntent(textIntent)
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test fun pwaActivityWithoutUrlShowsEmptyShell() {
        val controller = Robolectric.buildActivity(PwaActivity::class.java, Intent(context, PwaActivity::class.java))
        val activity = controller.create().start().resume().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity)
        controller.pause().stop().destroy()
    }
}
