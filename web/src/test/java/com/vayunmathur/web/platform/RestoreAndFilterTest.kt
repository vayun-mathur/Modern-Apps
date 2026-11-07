package com.vayunmathur.web.platform

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import android.webkit.WebView
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class RestoreAndFilterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries().build()
    private val stores = TestStores(db)
    private val createdVms = mutableListOf<WebViewModel>()
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest fun cleanup() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
        context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Secure.putInt(context.contentResolver, "browser_content_filters_enabled", 0)
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

    private fun prefs() = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)

    @Test fun restoresSavedTabsAndScalarPrefs() {
        val saved = listOf(
            BrowserTab(id = "t1", url = "https://one.example", title = "One"),
            BrowserTab(id = "t2", url = "https://two.example", title = "Two"),
        )
        prefs().edit()
            .putString("web_saved_tabs", json.encodeToString(saved))
            .putString("web_active_tab_id", "t2")
            .putString("web_cache_mode", "NO_CACHE")
            .putString("web_search_engine", "BING")
            .putBoolean("web_js_enabled", false)
            .putBoolean("web_block_third_party", true)
            .putBoolean("web_desktop_mode", true)
            .putBoolean("web_search_bar_bottom", false)
            .putString("web_shield_level", "STANDARD")
            .putBoolean("web_shield_trackers", false)
            .commit()
        val v = WebViewModel(stores, context, windowId = WebViewModel.DEFAULT_WINDOW_ID, incognito = false)
        createdVms += v
        await("tabs restored") { v.tabs.map { it.id }.containsAll(listOf("t1", "t2")) }
        assertEquals("t2", v.activeTabId)
        assertEquals("https://two.example", v.omniboxText)
        assertEquals(CacheMode.NO_CACHE, v.cacheMode)
        assertEquals(SearchEngine.BING, v.searchEngine)
        assertEquals(false, v.jsEnabled)
        assertEquals(true, v.blockThirdPartyCookies)
        assertEquals(true, v.desktopMode)
        assertEquals(false, v.searchBarAtBottom)
        assertEquals(ShieldLevel.STANDARD, v.shields.level)
        assertEquals(false, v.shields.blockTrackers)
        // Restored tabs are marked restored (external redirects are gated).
        assertTrue(v.restoredTabIds.contains("t1"))
    }

    @Test fun legacyHomepageTabsMigrateToBlank() {
        val saved = listOf(
            BrowserTab(id = "legacy", url = "https://duckduckgo.com", title = ""),
            BrowserTab(id = "real", url = "https://real.example", title = "Real"),
        )
        prefs().edit()
            .putString("web_saved_tabs", json.encodeToString(saved))
            .putString("web_active_tab_id", "real")
            .commit()
        val v = WebViewModel(stores, context, windowId = WebViewModel.DEFAULT_WINDOW_ID, incognito = false)
        createdVms += v
        await("migrated") { v.tabs.any { it.id == "real" } }
        val legacy = v.tabs.first { it.id == "legacy" }
        assertEquals("", legacy.url)
        assertEquals("", legacy.title)
    }

    @Test fun corruptSavedTabsFallBackToBlankTab() {
        prefs().edit().putString("web_saved_tabs", "{not json").commit()
        val v = WebViewModel(stores, context, windowId = WebViewModel.DEFAULT_WINDOW_ID, incognito = false)
        createdVms += v
        await("blank fallback") { v.tabs.isNotEmpty() }
        assertTrue(v.activeTab!!.isNewTab)
    }

    @Test fun incognitoIgnoresSavedTabs() {
        val saved = listOf(BrowserTab(id = "t9", url = "https://nine.example", title = "Nine"))
        prefs().edit().putString("web_saved_tabs", json.encodeToString(saved)).commit()
        val v = WebViewModel(stores, context, windowId = "incog-test", incognito = true)
        createdVms += v
        await("incognito blank") { v.tabs.isNotEmpty() }
        assertTrue(v.tabs.none { it.id == "t9" })
        assertTrue(v.tabs.all { it.isPrivate })
    }

    @Test fun persistedTabsRoundTripThroughOnCleared() {
        prefs().edit().clear().commit()
        val v = WebViewModel(stores, context, windowId = "roundtrip-window", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        v.newTab(url = "https://round.example/page")
        v.onClearedPersist()
        val sp = prefs()
        val savedJson = sp.getString("web_saved_tabs_roundtrip-window", null)
        assertNotNull(savedJson)
        val decoded = json.decodeFromString<List<BrowserTab>>(savedJson)
        assertTrue(decoded.any { it.url == "https://round.example/page" })
        assertEquals(v.activeTabId, sp.getString("web_active_tab_id_roundtrip-window", null))
    }

    private fun request(url: String, mainFrame: Boolean): android.webkit.WebResourceRequest =
        object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = true
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test fun contentFilterBlocksListedMainFrameOnly() {
        Settings.Secure.putInt(context.contentResolver, "browser_content_filters_enabled", 1)
        try {
            var blockedCalls = 0
            val client = ShieldsWebViewClient(
                context,
                shieldsFor = { EffectiveShields.OFF },
                onBlocked = { _, _ -> blockedCalls++ },
            )
            val view = WebView(context)
            // A listed domain (seed list in assets) on a main-frame load is blocked.
            val blocked = client.shouldInterceptRequest(view, request("https://pornhub.com/page", mainFrame = true))
            assertNotNull(blocked)
            assertEquals("text/html", blocked.mimeType)
            assertTrue(blockedCalls >= 1)
            // The same host as a subresource is not the filter's business.
            assertNull(client.shouldInterceptRequest(view, request("https://pornhub.com/ad.js", mainFrame = false)))
            // An unlisted host passes the filter (shields are OFF, so nothing else blocks).
            assertNull(client.shouldInterceptRequest(view, request("https://example.com/page", mainFrame = true)))
        } finally {
            Settings.Secure.putInt(context.contentResolver, "browser_content_filters_enabled", 0)
        }
    }

    @Test fun shieldsClientOnPageStartedAppliesCosmeticPath() {
        val client = ShieldsWebViewClient(
            context,
            shieldsFor = { EffectiveShields.resolve(com.vayunmathur.web.domain.ShieldsSettings.AGGRESSIVE_DEFAULTS) },
        )
        val view = WebView(context)
        client.onPageStarted(view, "https://cosmetic.example/page", null)
        client.onPageStarted(view, "about:blank", null)
        client.onPageStarted(view, null, null)
        // Farbling registration is feature-gated; calling it must not crash.
        client.installFarbling(view, com.vayunmathur.web.platform.shields.FarblingConfig.of(com.vayunmathur.web.domain.ShieldsSettings.AGGRESSIVE_DEFAULTS, emptyMap()))
    }
}
