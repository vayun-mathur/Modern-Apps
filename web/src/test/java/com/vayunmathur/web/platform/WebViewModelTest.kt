package com.vayunmathur.web.platform

import android.os.Looper
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.WebBookmarkStore
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.data.WebDownloadStore
import com.vayunmathur.web.data.WebHistoryStore
import com.vayunmathur.web.data.WebInstalledSiteStore
import com.vayunmathur.web.data.WebShieldStore
import com.vayunmathur.web.data.WebSitePermissionStore
import com.vayunmathur.web.data.WebStorageStore
import com.vayunmathur.web.data.WebStores
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

class TestStores(db: WebDatabase) : WebStores {
    override val history = WebHistoryStore(db.historyDao())
    override val bookmarks = WebBookmarkStore(db.bookmarkDao())
    override val permissions = WebSitePermissionStore(db.sitePermissionDao())
    override val storage = WebStorageStore(db.storageInfoDao())
    override val downloads = WebDownloadStore(db.downloadDao())
    override val installed = WebInstalledSiteStore(db.installedSiteDao())
    override val shields = WebShieldStore(db.shieldSettingDao())
}

@RunWith(RobolectricTestRunner::class)
class WebViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: WebDatabase = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries().build()
    internal val stores = TestStores(db)

    private val createdVms = mutableListOf<WebViewModel>()

    @AfterTest fun closeDb() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
        db.close()
    }

    internal fun vm(incognito: Boolean = true, windowId: String = "test-${System.nanoTime()}"): WebViewModel {
        val v = WebViewModel(stores, context, windowId = windowId, incognito = incognito)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    internal fun awaitCondition(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@runBlocking
                delay(10)
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(condition(), "timed out waiting for: $description")
        }
    }

    @Test fun newTabActivatesAndSetsOmnibox() {
        val v = vm()
        v.newTab(url = "https://example.com")
        assertEquals(v.tabs.last().id, v.activeTabId)
        assertEquals("https://example.com", v.activeTab?.url)
        assertEquals("https://example.com", v.omniboxText)
        assertTrue(v.activeTab!!.isPrivate)
    }

    @Test fun closeActiveTabSelectsNeighbourAndCreatesBlankWhenEmpty() {
        val v = vm()
        v.newTab(url = "https://a.example"); v.newTab(url = "https://b.example"); v.newTab(url = "https://c.example")
        val before = v.tabs.size
        v.switchToTab(v.tabs[1].id)
        v.closeTab(v.tabs[1].id)
        assertEquals(before - 1, v.tabs.size)
        assertNotNull(v.activeTab)
        while (v.tabs.size > 1) v.closeTab(v.tabs[0].id)
        v.closeTab(v.tabs[0].id)
        assertEquals(1, v.tabs.size)
        assertTrue(v.activeTab!!.isNewTab)
    }

    @Test fun closeUnknownAndMoveValidation() {
        val v = vm()
        v.newTab(url = "https://a.example"); v.newTab(url = "https://b.example"); v.newTab(url = "https://c.example")
        val size = v.tabs.size
        v.closeTab("nope")
        assertEquals(size, v.tabs.size)
        val order = v.tabs.map { it.url }
        val last = order.lastIndex
        v.moveTab(0, last)
        assertEquals(order.drop(1) + order.first(), v.tabs.map { it.url })
        val after = v.tabs.map { it.url }
        v.moveTab(0, 0); v.moveTab(-1, 1); v.moveTab(0, 99)
        assertEquals(after, v.tabs.map { it.url })
    }

    @Test fun tabStateAccessorsRoundTrip() {
        val v = vm()
        v.newTab(url = "https://a.example")
        val id = v.activeTabId!!
        v.onTabProgress(id, 0.5f); v.onTabCanGoBack(id, true); v.onTabCanGoForward(id, false)
        v.onTabTitleChange(id, "Title A")
        assertEquals(0.5f, v.getProgress(id))
        assertTrue(v.getCanGoBack(id)); assertFalse(v.getCanGoForward(id))
        assertEquals("Title A", v.getTabTitle(id))
        assertEquals(0f, v.getProgress("missing"))
        assertEquals("", v.getTabTitle("missing"))
        v.onTabUrlChange(id, "https://a.example/next")
        assertEquals("https://a.example/next", v.activeTab?.url)
        assertEquals("https://a.example/next", v.getCurrentUrl(id))
    }

    @Test fun navigateActiveTabSearchesAndNavigates() {
        val v = vm()
        v.newTab()
        v.navigateActiveTab("example.com")
        assertEquals("https://example.com", v.activeTab?.url)
        v.navigateActiveTab("cute cats")
        assertTrue(v.activeTab!!.url.contains("duckduckgo"), v.activeTab!!.url)
        assertFalse(v.omniboxFocused)
        v.externalIntentUrl("https://ext.example")
        assertEquals("https://ext.example", v.activeTab?.url)
    }

    @Test fun settingsUpdatesApply() {
        val v = vm()
        v.updateCacheMode(CacheMode.NO_CACHE); assertEquals(CacheMode.NO_CACHE, v.cacheMode)
        v.updateJsEnabled(false); assertFalse(v.jsEnabled)
        v.updateBlockThirdParty(true); assertTrue(v.blockThirdPartyCookies)
        v.updateDesktopMode(true); assertTrue(v.desktopMode)
        v.updateSearchBarAtBottom(false); assertFalse(v.searchBarAtBottom)
        v.updateSearchEngine(SearchEngine.BING); assertEquals(SearchEngine.BING, v.searchEngine)
        assertEquals(SearchEngine.BING.homepage, v.homepage)
    }

    @Test fun shieldsForSiteOverrideAndIncognito() {
        val v = vm(incognito = false)
        v.updateSiteShields("x.example", ShieldsSettings(level = ShieldLevel.OFF))
        assertEquals(ShieldLevel.OFF, v.shieldsFor("x.example").level)
        assertEquals(ShieldLevel.AGGRESSIVE, v.shieldsFor("other.example").level)
        val priv = vm(incognito = true)
        assertEquals(ShieldLevel.AGGRESSIVE, priv.shieldsFor("x.example", isPrivate = true).level)
        assertTrue(v.farblingConfig().enabled)
    }

    @Test fun siteShieldsBlankHostIgnoredAndClearRemoves() {
        val v = vm(incognito = false)
        v.updateSiteShields("", ShieldsSettings(level = ShieldLevel.OFF))
        assertTrue(v.shieldOverrides.isEmpty())
        v.updateSiteShields("x.example", ShieldsSettings(blockTrackers = false))
        assertEquals(false, v.shieldsFor("x.example").blockTrackers)
        v.updateSiteShields("x.example", ShieldsSettings())
        assertFalse(v.shieldOverrides.containsKey("x.example"))
        v.updateSiteShields("y.example", ShieldsSettings(blockTrackers = false))
        v.clearSiteShields()
        assertTrue(v.shieldOverrides.isEmpty())
    }

    @Test fun blockedCountCoalescesAndResets() {
        val v = vm()
        v.newTab(url = "https://a.example")
        val id = v.activeTabId!!
        v.onRequestBlocked(id); v.onRequestBlocked(id)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, v.blockedCount(id))
        v.resetBlockedCount(id)
        assertEquals(0, v.blockedCount(id))
    }

    @Test fun externalRedirectGatingForRestoredTabs() {
        val v = vm()
        v.newTab(url = "https://a.example")
        val id = v.activeTabId!!
        assertTrue(v.allowExternalRedirect(id, userGesture = false))
        v.restoredTabIds.add(id)
        assertFalse(v.allowExternalRedirect(id, userGesture = false))
        assertTrue(v.allowExternalRedirect(id, userGesture = true))
        assertTrue(v.allowExternalRedirect(id, userGesture = false))
        v.restoredTabIds.add(id)
        v.markFreshNavigation(id)
        assertTrue(v.allowExternalRedirect(id, userGesture = false))
    }

    @Test fun pwaInfoAndFileChooserLifecycle() {
        val v = vm()
        v.newTab()
        val id = v.activeTabId!!
        assertNull(v.getPwaInfo(id))
        val info = PwaInfo(name = "App", origin = "https://x.example")
        v.onPwaInfoDetected(id, info)
        assertEquals(info, v.getPwaInfo(id))
        assertNull(v.thumbnailFor(id))
        v.captureThumbnail(id, android.webkit.WebView(context))
        var received: Array<android.net.Uri>? = arrayOf(android.net.Uri.parse("file:///x"))
        val callback = android.webkit.ValueCallback<Array<android.net.Uri>> { received = it }
        val params = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = 0
            override fun getAcceptTypes(): Array<String> = arrayOf("image/*")
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): android.content.Intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
        }
        v.requestFileChooser(callback, params)
        assertNotNull(v.pendingFileChooser)
        v.deliverFileChooserResult(null)
        assertNull(v.pendingFileChooser)
        assertNull(received)
        // Clearing with nothing pending is harmless.
        v.clearFileChooser()
    }

    @Test fun localNetworkPromptClearDenial() {
        val v = vm()
        v.clearLocalNetworkPrompt(denied = true)
        assertNull(v.pendingLocalNetworkHost)
        assertTrue(v.localNetworkDenied)
        val v2 = vm()
        v2.clearLocalNetworkPrompt(denied = false)
        assertNull(v2.pendingLocalNetworkHost)
    }

    @Test fun historyBookmarkAndDownloadFlowsReachRepository() {
        val v = vm(incognito = false)
        v.newTab(url = "https://hist.example")
        v.recordHistoryVisit("https://hist.example/page", "Hist", isPrivateTab = false)
        v.recordHistoryVisit("", "blank", isPrivateTab = false)
        v.recordHistoryVisit("about:blank", "blank", isPrivateTab = false)
        v.addBookmark("https://bm.example", "BM")
        v.createFolder("Folder1")
        v.addDownload("https://dl.example/f.txt", "f.txt", "text/plain", 12)
        awaitCondition("history recorded") { v.history.value.any { it.url == "https://hist.example/page" } }
        awaitCondition("bookmark recorded") { v.bookmarks.value.any { it.url == "https://bm.example" } }
        awaitCondition("folder recorded") { v.folders.value.any { it.name == "Folder1" } }
        awaitCondition("download recorded") { v.downloads.value.any { it.fileName == "f.txt" } }
        val bm = v.bookmarks.value.first { it.url == "https://bm.example" }
        v.removeBookmark(bm)
        awaitCondition("bookmark removed") { v.bookmarks.value.none { it.url == "https://bm.example" } }
        v.clearHistory()
        awaitCondition("history cleared") { v.history.value.none { it.url == "https://hist.example/page" } }
        v.clearAllDownloads()
        awaitCondition("downloads cleared") { v.downloads.value.isEmpty() }
    }

    @Test fun historyVisitUsesOwningTabPrivacyNotActiveTab() {
        val v = vm(incognito = false)
        v.newTab(url = "https://public.example/")
        v.newTab(url = "https://secret.example/", isPrivate = true, makeActive = false)
        // The private tab finishes loading while the normal tab stays active.
        assertEquals("https://public.example/", v.activeTab?.url)
        v.recordHistoryVisit("https://secret.example/", "Secret", isPrivateTab = true)
        v.recordHistoryVisit("https://public.example/", "Public", isPrivateTab = false)
        awaitCondition("public visit recorded") { v.history.value.any { it.url == "https://public.example/" } }
        assertTrue(
            v.history.value.none { it.url == "https://secret.example/" },
            "private tab visit leaked into history while another tab was active",
        )
    }

    @Test fun historyVisitRecordsBackgroundNormalTabWhilePrivateTabActive() {
        val v = vm(incognito = false)
        v.newTab(url = "https://public.example/", makeActive = false)
        v.newTab(url = "https://secret.example/", isPrivate = true)
        assertTrue(v.activeTab?.isPrivate == true)
        v.recordHistoryVisit("https://public.example/", "Public", isPrivateTab = false)
        awaitCondition("background normal visit recorded") {
            v.history.value.any { it.url == "https://public.example/" }
        }
    }

    @Test fun folderDeletionRemovesItsBookmarks() {
        val v = vm(incognito = false)
        v.createFolder("ToDelete")
        awaitCondition("folder created") { v.folders.value.any { it.name == "ToDelete" } }
        val folder = v.folders.value.first { it.name == "ToDelete" }
        v.addBookmark("https://infolder.example", "In", folderId = folder.id)
        awaitCondition("bookmark in folder") { v.bookmarks.value.any { it.url == "https://infolder.example" } }
        v.deleteFolder(folder)
        awaitCondition("folder deleted") { v.folders.value.none { it.name == "ToDelete" } }
        awaitCondition("folder bookmarks deleted") { v.bookmarks.value.none { it.url == "https://infolder.example" } }
    }

    @Test fun permissionPromptUsesSavedDecisions() {
        val v = vm(incognito = false)
        runBlocking { stores.permissions.upsert(com.vayunmathur.web.data.SitePermission(origin = "https://perm.example", cameraAllowed = true, microphoneAllowed = false)) }
        var granted: List<SitePermissionType>? = null
        var denied = false
        v.requestWebPermission("https://perm.example", listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE), { granted = it }, { denied = true })
        awaitCondition("saved permission granted") { granted != null || denied }
        assertEquals(listOf(SitePermissionType.CAMERA), granted)
        assertFalse(denied)
    }

    @Test fun permissionPromptForUnknownOriginAsks() {
        val v = vm(incognito = false)
        v.requestWebPermission("https://unknown.example", listOf(SitePermissionType.CAMERA), {}, {})
        awaitCondition("prompt shown") { v.pendingPermissionPrompt != null }
        assertEquals("https://unknown.example", v.pendingPermissionPrompt?.origin)
        assertEquals(listOf(SitePermissionType.CAMERA), v.pendingPermissionPrompt?.types)
        v.pendingPermissionPrompt?.onGrant?.invoke(listOf(SitePermissionType.CAMERA))
        v.clearPermissionPrompt()
        assertNull(v.pendingPermissionPrompt)
        awaitCondition("permission persisted") {
            runBlocking { stores.permissions.byOrigin("https://unknown.example")?.cameraAllowed == true }
        }
    }

    @Test fun geolocationSavedDecisionAndPromptFlow() {
        val v = vm(incognito = false)
        runBlocking { stores.permissions.upsert(com.vayunmathur.web.data.SitePermission(origin = "https://geo.example", locationAllowed = true)) }
        var allowed = false
        v.requestGeolocation("https://geo.example", { allowed = true }, {})
        awaitCondition("geo allowed") { allowed }
        var promptAllowed = false
        v.requestGeolocation("https://geo2.example", { promptAllowed = true }, {})
        awaitCondition("geo prompt") { v.pendingGeolocationPrompt != null }
        v.grantGeolocation()
        assertTrue(promptAllowed)
        assertNull(v.pendingGeolocationPrompt)
        var promptDenied = false
        v.requestGeolocation("https://geo3.example", {}, { promptDenied = true })
        awaitCondition("geo prompt 2") { v.pendingGeolocationPrompt != null }
        v.denyGeolocation()
        assertTrue(promptDenied)
    }

    @Test fun revokePermissionDeletesLastDecision() {
        val v = vm(incognito = false)
        runBlocking { stores.permissions.upsert(com.vayunmathur.web.data.SitePermission(origin = "https://rev.example", cameraAllowed = true)) }
        v.revokePermission("https://rev.example", SitePermissionType.CAMERA)
        awaitCondition("permission row deleted") {
            runBlocking { stores.permissions.byOrigin("https://rev.example") == null }
        }
        // Revoking an unknown origin is a no-op.
        v.revokePermission("https://nope.example", SitePermissionType.CAMERA)
    }

    @Test fun storageFootprintAndSiteDataClear() {
        val v = vm(incognito = false)
        v.updateStorageFootprint("https://store.example", cookieCount = 2, hasLocalStorage = true, hasIndexedDb = false, hasServiceWorker = false, estBytes = 100)
        awaitCondition("storage recorded") { v.storageInfos.value.any { it.origin == "https://store.example" && it.cookieCount == 2 } }
        v.updateStorageFootprint("https://store.example", cookieCount = 5, hasLocalStorage = false, hasIndexedDb = true, hasServiceWorker = false, estBytes = 0)
        awaitCondition("storage merged") {
            v.storageInfos.value.any { it.origin == "https://store.example" && it.cookieCount == 5 && it.hasLocalStorage && it.hasIndexedDb && it.estimatedBytes == 100L }
        }
        v.clearSiteData("https://store.example")
        awaitCondition("storage cleared") { v.storageInfos.value.none { it.origin == "https://store.example" } }
    }

    @Test fun siteDataClearAlsoClearsWebViewState() {
        val cleared = mutableListOf<String>()
        val v = WebViewModel(stores, context, incognito = false, originStateClearer = OriginStateClearer { cleared += it })
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        val origin = "https://cookie-clear.example"
        v.updateStorageFootprint(origin, cookieCount = 2, hasLocalStorage = true, hasIndexedDb = false, hasServiceWorker = false, estBytes = 100)
        awaitCondition("storage recorded") { v.storageInfos.value.any { it.origin == origin } }
        v.clearSiteData(origin)
        awaitCondition("origin web state cleared") { origin in cleared }
        awaitCondition("storage cleared") { v.storageInfos.value.none { it.origin == origin } }
    }

    @Test fun shieldsPersistThroughRepository() {
        val v = vm(incognito = false)
        v.updateShields(ShieldsSettings(level = ShieldLevel.STANDARD, blockTrackers = false, cosmeticFiltering = true, fingerprintProtection = true, httpsUpgrade = true))
        assertEquals(ShieldLevel.STANDARD, v.shields.level)
        v.updateSiteShields("persist.example", ShieldsSettings(blockTrackers = false))
        awaitCondition("shield setting persisted") {
            runBlocking { stores.shields.byHost("persist.example") != null }
        }
        v.clearSiteShields()
        awaitCondition("shield settings cleared") {
            runBlocking { stores.shields.byHost("persist.example") == null }
        }
    }

    @Test fun installAndRemovePwa() {
        val v = vm(incognito = false)
        v.newTab(url = "https://pwa.example/app")
        val id = v.activeTabId!!
        var result: Boolean? = null
        v.installAsPwa(id, "https://pwa.example/app", PwaInfo(name = "PWA", origin = "https://pwa.example")) { result = it }
        awaitCondition("pwa installed") { v.installedSites.value.any { it.url == "https://pwa.example/app" } }
        val site = v.installedSites.value.first { it.url == "https://pwa.example/app" }
        assertEquals("PWA", site.title)
        v.removeInstalledSite(site.id)
        awaitCondition("pwa removed") { v.installedSites.value.none { it.id == site.id } }
    }
}
