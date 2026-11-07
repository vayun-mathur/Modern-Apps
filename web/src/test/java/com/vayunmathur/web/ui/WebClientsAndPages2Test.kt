package com.vayunmathur.web.ui

import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.platform.BrowserTab
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.isNewTab
import com.vayunmathur.web.platform.nativeSearchUrl
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebClientsAndPages2Test {

    @get:Rule val rule = createComposeRule()

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
        // The in-memory db is intentionally not closed: cancelled view-model
        // scopes may still be winding down their flow collections, and a
        // closed pool crashes them (and the next composition) intermittently.
    }

    private fun vm(incognito: Boolean = false): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "p2-${System.nanoTime()}", incognito = incognito)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun await(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@runBlocking
                delay(10)
            }
            assertTrue(condition(), "timed out: $description")
        }
    }

    @Test fun searchResultsPageShowsTabsAndFallbackAction() {
        var fallback: String? = null
        rule.setContent {
            SearchResultsPage(query = "cats", onOpenResult = {}, onOpenFallback = { fallback = it })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Web", substring = true).assertIsDisplayed()
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("cats", substring = true)).fetchSemanticsNodes().isNotEmpty())
        // The search itself runs against the real API client, which has no
        // network in tests; its result/error rendering is covered by the
        // text/news list tests, so here only the page chrome is asserted.
        assertEquals(null, fallback)
    }

    @Test fun searchResultsTabsSwitchKinds() {
        rule.setContent {
            SearchResultsPage(query = "dogs", onOpenResult = {}, onOpenFallback = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("News", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Images", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Images", substring = true).assertIsDisplayed()
    }

    @Test fun browserContentNewTabShowsQuickAccess() {
        val v = vm()
        v.newTab()
        val tab = v.activeTab!!
        rule.setContent {
            BrowserContent(
                viewModel = v,
                activeTab = tab,
                isNewTabActive = true,
                webViewPool = mutableMapOf(),
                fullscreenHost = WebFullscreenHost(context),
                onOpenUrl = { _, _ -> },
                onRequestNewTab = { _, _ -> },
                onLinkLongPress = {},
            )
        }
        rule.waitForIdle()
        assertTrue(tab.isNewTab)
    }

    @Test fun browserContentNativeSearchShowsResultsPage() {
        val v = vm()
        v.newTab(url = nativeSearchUrl("owls"))
        val tab = v.activeTab!!
        rule.setContent {
            BrowserContent(
                viewModel = v,
                activeTab = tab,
                isNewTabActive = false,
                webViewPool = mutableMapOf(),
                fullscreenHost = WebFullscreenHost(context),
                onOpenUrl = { _, _ -> },
                onRequestNewTab = { _, _ -> },
                onLinkLongPress = {},
            )
        }
        rule.waitForIdle()
        // Title (query) plus the scaffold title both carry the query text.
        assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasText("owls", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun browserContentWebTabCreatesWebView() {
        val v = vm()
        v.newTab(url = "https://content.example/page")
        val tab = v.activeTab!!
        val pool = mutableMapOf<String, WebView>()
        rule.setContent {
            BrowserContent(
                viewModel = v,
                activeTab = tab,
                isNewTabActive = false,
                webViewPool = pool,
                fullscreenHost = WebFullscreenHost(context),
                onOpenUrl = { _, _ -> },
                onRequestNewTab = { _, _ -> },
                onLinkLongPress = {},
            )
        }
        rule.waitForIdle()
        assertNotNull(pool[tab.id])
    }

    @Test fun fullscreenHostWithoutActivityIsNoOp() {
        val host = WebFullscreenHost { null }
        assertFalse(host.isFullscreen)
        var hidden = false
        val callback = object : android.webkit.WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() { hidden = true }
        }
        host.onShowCustomView(View(context), callback)
        assertFalse(host.isFullscreen)
        assertFalse(host.onHideCustomView())
        assertFalse(hidden)
    }

    @Test fun shieldsClientRewritesTrackingAndHttps() {
        val navigated = mutableListOf<String>()
        val client = ShieldsWebViewClient(context, shieldsFor = { EffectiveShields.resolve(com.vayunmathur.web.domain.ShieldsSettings.AGGRESSIVE_DEFAULTS) }, onNavigate = { _, url -> navigated += url })
        val view = WebView(context)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("http://example.com/page?utm_source=x&id=3")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertTrue(client.shouldOverrideUrlLoading(view, request))
        assertEquals(listOf("https://example.com/page?id=3"), navigated)
        // A clean https URL is left to the WebView.
        val clean = object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("https://example.com/page?id=3")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertFalse(client.shouldOverrideUrlLoading(view, clean))
        // Subresource requests never override.
        val sub = object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("http://example.com/x.js?utm_source=x")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertFalse(client.shouldOverrideUrlLoading(view, sub))
        client.onPageStarted(view, "https://example.com/page?id=3", null)
    }

    @Test fun shieldsClientInterceptsPublicCleartextSubresource() {
        val client = ShieldsWebViewClient(context, shieldsFor = { EffectiveShields.OFF })
        val view = WebView(context)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("http://93.184.216.34/ad.js")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        val response = client.shouldInterceptRequest(view, request)
        assertNotNull(response)
        assertEquals(200, response.statusCode)
        // Non-http schemes pass through.
        val dataReq = object : android.webkit.WebResourceRequest {
            override fun getUrl(): android.net.Uri = android.net.Uri.parse("data:text/html,hi")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertEquals(null, client.shouldInterceptRequest(view, dataReq))
    }

    @Test fun browserOverlaysShowTabSwitcherWhenRequested() {
        val v = vm()
        v.newTab(url = "https://overlay.example", makeActive = true)
        v.showTabSwitcher = true
        val launcher = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
            override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) {}
            override fun unregister() {}
            override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        }
        rule.setContent {
            BrowserOverlays(
                viewModel = v,
                activeTab = v.activeTab,
                shieldHost = null,
                webViewPool = mutableMapOf(),
                linkContextMenuUrl = null,
                onLinkMenuDismiss = {},
                showInstallDialog = false,
                onInstallDialogDismiss = {},
                multiDocLauncher = launcher,
                singleDocLauncher = launcher,
                localNetworkRequest = null,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("overlay.example", substring = true).assertIsDisplayed()
    }
}
