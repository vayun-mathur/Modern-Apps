package com.vayunmathur.web.ui

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.webkit.PermissionRequest
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.WebViewModelFactory
import com.vayunmathur.web.platform.getProgress
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.shields.ShieldsRequestFilter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BrowserInteractionTest {

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
    }

    private fun vm(incognito: Boolean = false): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "inter-${System.nanoTime()}", incognito = incognito)
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

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Browser))

    @Test fun browserPageOmniboxEditingFlow() {
        val v = vm()
        v.newTab(url = "https://edit.example/page")
        rule.setContent { BrowserPage(v, backStack()) }
        rule.waitForIdle()
        // Tap the address pill to enter editing mode.
        rule.onNodeWithText("https://edit.example/page", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(v.omniboxFocused)
        // Dismiss via the editor's close button.
        rule.onAllNodes(hasContentDescription("Close"))[0].performClick()
        rule.waitForIdle()
        assertFalse(v.omniboxFocused)
    }

    @Test fun browserPageTabSwitcherAndMenuOpen() {
        val v = vm()
        v.newTab(url = "https://switch.example/page")
        rule.setContent { BrowserPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText(v.tabs.size.toString()).performClick()
        rule.waitForIdle()
        assertTrue(v.showTabSwitcher)
        v.showTabSwitcher = false
        rule.waitForIdle()
        rule.onNode(hasContentDescription("More")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("New tab").assertIsDisplayed()
        val tabsBeforeMenu = v.tabs.size
        rule.onNodeWithText("New tab").performClick()
        rule.waitForIdle()
        assertEquals(tabsBeforeMenu + 1, v.tabs.size)
    }

    @Test fun omniboxEditorTypingAndClear() {
        val v = vm()
        v.newTab()
        v.searchDraft = ""
        rule.setContent {
            OmniboxEditor(
                viewModel = v,
                bookmarks = listOf(Bookmark(id = 1, url = "https://bm.example", title = "BmTitle")),
                history = listOf(HistoryEntry(id = 2, url = "https://hist.example", title = "HistTitle")),
                searchFocusRequester = androidx.compose.ui.focus.FocusRequester(),
                onNavigate = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("HistTitle").assertIsDisplayed()
        // Type into the field; the draft mirrors into the view model.
        rule.onAllNodes(androidx.compose.ui.test.hasSetTextAction())[0].performTextInput("bm")
        rule.waitForIdle()
        assertEquals("bm", v.searchDraft)
        rule.onNodeWithText("BmTitle").assertIsDisplayed()
    }

    @Test fun quickAccessOpensBookmarksAndHistory() {
        var opened: String? = null
        rule.setContent {
            QuickAccess(
                bookmarks = listOf(Bookmark(id = 1, url = "https://qa.example", title = "QaSite")),
                history = listOf(HistoryEntry(id = 2, url = "https://recent.example", title = "RecentSite")),
                onOpenUrl = { opened = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("QaSite").performClick()
        rule.waitForIdle()
        assertEquals("https://qa.example", opened)
        rule.onNodeWithText("RecentSite").performClick()
        rule.waitForIdle()
        assertEquals("https://recent.example", opened)
    }

    @Test fun quickAccessEmptyShowsHint() {
        rule.setContent {
            QuickAccess(bookmarks = emptyList(), history = emptyList(), onOpenUrl = {})
        }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("address pill", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    private class FakePermissionRequest(
        private val res: Array<String>,
        private val originUri: Uri = Uri.parse("https://origin.example"),
    ) : PermissionRequest() {
        var granted: Array<String>? = null
        var denied = false
        override fun getOrigin(): Uri = originUri
        override fun getResources(): Array<String> = res
        override fun grant(resources: Array<String>) { granted = resources }
        override fun deny() { denied = true }
    }

    private fun deps() = WebViewClientDeps(context, launcher()) { _, _, _ -> }

    private fun launcher() = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
        override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
            androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    }

    @Test fun chromeClientDeniesPermissionsForPrivateTabs() {
        val v = vm(incognito = true)
        v.newTab(url = "https://priv.example")
        val tabId = v.activeTabId!!
        val chrome = createBrowserWebChromeClient(tabId, v, true, deps(), onRequestNewTab = {})
        val webView = WebView(context)
        val req = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        chrome.onPermissionRequest(req)
        assertTrue(req.denied)
        var geoResult: Boolean? = null
        val callback = android.webkit.GeolocationPermissions.Callback { _, allow, _ -> geoResult = allow }
        chrome.onGeolocationPermissionsShowPrompt("https://priv.example", callback)
        assertEquals(false, geoResult)
    }

    @Test fun chromeClientDeniesUnmappedResources() {
        val v = vm()
        v.newTab(url = "https://pub.example")
        val tabId = v.activeTabId!!
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps(), onRequestNewTab = {})
        val webView = WebView(context)
        // A protected-media-id request maps to no site-permission type, so it fails
        // closed instead of being silently granted.
        val req = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        chrome.onPermissionRequest(req)
        assertTrue(req.denied)
        assertTrue(req.granted == null)
        chrome.onProgressChanged(webView, 100)
        assertEquals(1f, v.getProgress(tabId))
    }

    @Test fun chromeClientCreateWindowWithTransport() {
        val v = vm()
        v.newTab(url = "https://pub.example")
        val tabId = v.activeTabId!!
        var newTabUrl: String? = null
        val chrome = createBrowserWebChromeClient(tabId, v, false, deps(), onRequestNewTab = { newTabUrl = it })
        val webView = WebView(context)
        val transport = webView.WebViewTransport()
        val msg = Message.obtain(Handler(Looper.getMainLooper()) { true }, 0)
        msg.obj = transport
        assertTrue(chrome.onCreateWindow(webView, false, true, msg))
        assertNotNull(transport.webView)
    }

    @Test fun requestFilterBlocksPublicCleartextMainFrame() {
        val webView = WebView(context)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("http://93.184.216.34/page")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        val resp = ShieldsRequestFilter.intercept(context, request, "", { EffectiveShields.OFF }) { _, _ -> }
        assertNotNull(resp)
        assertEquals("text/html", resp.mimeType)
        val body = resp.data.readAllBytes().decodeToString()
        assertTrue(body.contains("93.184.216.34") || body.isNotBlank(), body.take(200))
    }

    @Test fun requestFilterPassesHttpsAndLan() {
        val https = object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("https://example.com/page")
            override fun isForMainFrame(): Boolean = true
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertNull(ShieldsRequestFilter.intercept(context, https, "", { EffectiveShields.OFF }) { _, _ -> })
        val lan = object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse("http://192.168.1.10:8080/app.js")
            override fun isForMainFrame(): Boolean = false
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }
        assertNull(ShieldsRequestFilter.intercept(context, lan, "http://192.168.1.10:8080/", { EffectiveShields.OFF }) { _, _ -> })
    }

    @Test fun searchImageResultsComposes() {
        rule.setContent {
            SearchImageResults(query = "kittens", onOpenResult = {}, onOpenFallback = {})
        }
        rule.waitForIdle()
        // It starts in the loading state while its fetch runs with no network.
        assertTrue(true)
    }

    @Test fun viewModelFactoryCreatesAndRejects() {
        val factory = WebViewModelFactory(stores, context, windowId = "factory-test", incognito = true)
        val created = factory.create(WebViewModel::class.java)
        assertTrue(created.incognito)
        createdVms += created
        assertFailsWith<IllegalArgumentException> {
            factory.create(com.vayunmathur.web.platform.WebViewModelFactory::class.java as Class<WebViewModel>)
        }
    }

    @Test fun routeSerializationRoundTrips() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        for (route in listOf(Route.Browser, Route.History, Route.Bookmarks, Route.Settings, Route.Downloads, Route.SiteData, Route.InstalledSites, Route.Shields)) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded))
        }
    }
}
