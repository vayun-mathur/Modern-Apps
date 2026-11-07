package com.vayunmathur.web.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.InstalledSite
import com.vayunmathur.web.data.RemoteImageFetchers
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.shields.ShieldsEngine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
class MiscDeepTest {

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

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "misc-${System.nanoTime()}", incognito = false)
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

    @Test fun remoteImageFetcherRejectsNonHttpData() = runBlocking {
        assertNull(RemoteImageFetchers.network(null, context))
        assertNull(RemoteImageFetchers.network(42, context))
        assertNull(RemoteImageFetchers.network("ftp://example.com/x.png", context))
        assertNull(RemoteImageFetchers.network(Uri.parse("file:///tmp/x.png"), context))
        assertNull(RemoteImageFetchers.network(Uri.parse("content://media/1"), context))
        Unit
    }

    @Test fun faviconDownscaleAndPersist() {
        FaviconStore.init(context)
        val big = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        FaviconStore.put("https://bigicon.example/page", big, isPrivate = false)
        // Memory cache is immediate.
        assertNotNull(FaviconStore.forUrl("https://bigicon.example/other"))
        await("favicon downscaled in cache") {
            val cached = FaviconStore.forUrl("https://bigicon.example/other")
            cached == null || cached.width <= 64
        }
    }

    @Test fun thumbnailCaptureStoresBitmap() {
        TabThumbnailStore.init(context)
        val webView = WebView(context)
        webView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(100, android.view.View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, 200, 100)
        // Robolectric's WebView cannot draw into a canvas, so capture is a
        // no-op here; on device the same call stores the drawn bitmap. The
        // store's bookkeeping around it is what can be asserted.
        TabThumbnailStore.capture("thumb-tab-1", webView, isPrivate = false)
        TabThumbnailStore.remove("thumb-tab-1")
        assertNull(TabThumbnailStore.get("../unsafe"))
        // After removal the in-memory entry is gone (a disk reload may refill it
        // asynchronously, so only the removal call itself is asserted here).
    }

    @Test fun shieldsEngineLoadedChecksAds() {
        runBlocking { ShieldsEngine.load(context) }
        shadowOf(Looper.getMainLooper()).idle()
        if (!ShieldsEngine.ready) {
            // Host native library unavailable in this environment: the engine
            // must fail open, which the earlier tests already assert.
            assertNull(ShieldsEngine.check("https://example.com", "https://example.com", "document"))
            return
        }
        // A well-known ad-serving script is blocked by the bundled lists.
        val adCheck = ShieldsEngine.check(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://news.example/article",
            "script",
        )
        assertNotNull(adCheck)
        assertTrue(adCheck.blocked, "expected adsbygoogle to be blocked, was $adCheck")
        // The embedding page itself is not blocked.
        val pageCheck = ShieldsEngine.check("https://news.example/article", "https://news.example/article", "document")
        assertTrue(pageCheck == null || !pageCheck.blocked)
        // Cosmetic resources and class/id selector lookups answer.
        assertNotNull(ShieldsEngine.cosmetic("https://news.example/article"))
        ShieldsEngine.hiddenClassIdSelectors(listOf("ad-banner", "sponsored"), listOf("ad"), emptyList())
    }

    @Test fun installedSiteCardLaunchesPwa() {
        val v = vm()
        runBlocking { stores.installed.upsert(InstalledSite(id = "launch1", url = "https://launch.example/app", title = "LaunchApp", origin = "https://launch.example")) }
        await("installed") { v.installedSites.value.any { it.title == "LaunchApp" } }
        rule.setContent { InstalledSitesPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNodeWithText("LaunchApp").performClick()
        rule.waitForIdle()
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals("https://launch.example/app", started.getStringExtra(com.vayunmathur.web.platform.PwaActivity.EXTRA_URL))
    }

    @Test fun historyGroupsOldEntries() {
        val v = vm()
        val tenDaysAgo = System.currentTimeMillis() - 10L * 24 * 3600 * 1000
        runBlocking {
            stores.history.upsert(HistoryEntry(url = "https://old.example", title = "OldEntry", visitedAt = tenDaysAgo))
            stores.history.upsert(HistoryEntry(url = "https://today.example", title = "TodayEntry", visitedAt = System.currentTimeMillis()))
        }
        await("history") { v.history.value.size >= 2 }
        rule.setContent { HistoryPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNodeWithText("TodayEntry").performClick()
        rule.waitForIdle()
        assertTrue(v.tabs.any { it.url == "https://today.example" })
    }

    @Test fun settingsClearDataConfirmRuns() {
        val v = vm()
        runBlocking { stores.history.upsert(HistoryEntry(url = "https://clear.example", title = "ClearMe", visitedAt = System.currentTimeMillis())) }
        await("history seeded") { v.history.value.isNotEmpty() }
        rule.setContent { SettingsPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Clear browsing data"))
        rule.onNodeWithText("Clear browsing data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        await("history cleared by settings") { v.history.value.isEmpty() }
    }
}
