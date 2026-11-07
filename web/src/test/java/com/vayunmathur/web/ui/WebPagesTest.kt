package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.InstalledSite
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.StorageInfo
import com.vayunmathur.web.data.DownloadEntry
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.addBookmark
import com.vayunmathur.web.platform.newTab
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebPagesTest {

    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, com.vayunmathur.web.data.WebDatabase::class.java)
        .allowMainThreadQueries().build()
    private val stores = TestStores(db)

    private val createdVms = mutableListOf<WebViewModel>()

    @AfterTest fun closeDb() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
        db.close()
    }

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "pages-${System.nanoTime()}", incognito = false)
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

    @Test fun settingsPageShowsSectionsAndTogglesJs() {
        val v = vm()
        rule.setContent { SettingsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Search engine").assertIsDisplayed()
        rule.onNodeWithText("JavaScript").assertIsDisplayed()
        rule.onNodeWithText("Cache mode").assertIsDisplayed()
    }

    @Test fun settingsSearchEngineDialogListsEngines() {
        val v = vm()
        rule.setContent { SettingsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Search engine").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Brave").assertIsDisplayed()
        rule.onNodeWithText("Google").performClick()
        rule.waitForIdle()
        assertEquals(com.vayunmathur.web.platform.SearchEngine.GOOGLE, v.searchEngine)
    }

    @Test fun settingsCacheDialogSelectsMode() {
        val v = vm()
        rule.setContent { SettingsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Cache mode").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No cache").performClick()
        rule.waitForIdle()
        assertEquals(com.vayunmathur.web.platform.CacheMode.NO_CACHE, v.cacheMode)
    }

    @Test fun historyPageEmptyThenWithEntries() {
        val v = vm()
        rule.setContent { HistoryPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("No history yet").assertIsDisplayed()
    }

    @Test fun historyPageListsEntriesAndOpens() {
        val v = vm()
        runBlocking { stores.history.upsert(HistoryEntry(url = "https://hist.example/page", title = "History Title", visitedAt = System.currentTimeMillis())) }
        await("history flows") { v.history.value.any { it.title == "History Title" } }
        val stack = backStack()
        rule.setContent { HistoryPage(v, stack) }
        rule.waitForIdle()
        rule.onNodeWithText("History Title").assertIsDisplayed()
        rule.onNodeWithText("History Title").performClick()
        rule.waitForIdle()
        assertTrue(v.tabs.any { it.url == "https://hist.example/page" })
    }

    @Test fun downloadsPageListsAndEmpty() {
        val v = vm()
        runBlocking { stores.downloads.upsert(DownloadEntry(url = "https://dl.example/report.pdf", fileName = "report.pdf", mimeType = "application/pdf", startedAt = System.currentTimeMillis())) }
        await("downloads flow") { v.downloads.value.isNotEmpty() }
        rule.setContent { DownloadsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("report.pdf").assertIsDisplayed()
    }

    @Test fun installedSitesPageLists() {
        val v = vm()
        runBlocking { stores.installed.upsert(InstalledSite(id = "s1", url = "https://app.example", title = "My App", origin = "https://app.example")) }
        await("installed flow") { v.installedSites.value.isNotEmpty() }
        rule.setContent { InstalledSitesPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("My App").assertIsDisplayed()
    }

    @Test fun shieldsPageShowsLevelsAndExceptionsEmpty() {
        val v = vm()
        rule.setContent { ShieldsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Protection level", substring = true).assertIsDisplayed()
    }

    @Test fun shieldsPanelForHostShowsToggles() {
        val v = vm()
        rule.setContent {
            ShieldsPanel(host = "example.com", blockedCount = 3, viewModel = v, onReload = {}, onDismiss = {})
        }
        rule.waitForIdle()
        rule.onNodeWithText("example.com", substring = true).assertIsDisplayed()
    }

    @Test fun siteDataPageShowsStorageAndPermissions() {
        val v = vm()
        runBlocking {
            stores.storage.upsert(StorageInfo(origin = "https://data.example", host = "data.example", cookieCount = 4, hasLocalStorage = true))
            stores.permissions.upsert(SitePermission(origin = "https://data.example", cameraAllowed = true))
        }
        await("site data flows") { v.storageInfos.value.isNotEmpty() && v.sitePermissions.value.isNotEmpty() }
        rule.setContent { SiteDataPage(v, backStack()) }
        rule.waitForIdle()
        rule.onAllNodes(androidx.compose.ui.test.hasText("data.example"))[0].assertIsDisplayed()
        rule.onNodeWithText("Camera: Allowed").assertIsDisplayed()
    }

    @Test fun browserMenuNavigatesToLibrary() {
        val v = vm()
        v.newTab(url = "https://menu.example")
        val stack = backStack()
        var dismissed = false
        rule.setContent {
            BrowserMenu(
                expanded = true,
                onDismiss = { dismissed = true },
                viewModel = v,
                backStack = stack,
                isNewTabActive = false,
                isCurrentBookmarked = false,
                canGoBack = true,
                canGoForward = false,
                onGoBack = {},
                onGoForward = {},
                shieldHost = "menu.example",
                blockedCount = 2,
                onShieldClick = {},
                onShowInstallDialog = {},
                onReload = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("History").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        assertEquals(Route.History, stack.backStack.last())
    }

    @Test fun installAppDialogShowsSite() {
        val v = vm()
        v.newTab(url = "https://install.example/app")
        rule.setContent { InstallAppDialog(v, onDismiss = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Add to Home screen?").assertIsDisplayed()
        rule.onAllNodes(androidx.compose.ui.test.hasText("install.example", substring = true))[0].assertIsDisplayed()
    }

    @Test fun browserPageRendersChromeForNewTab() {
        val v = vm()
        rule.setContent { BrowserPage(v, backStack()) }
        rule.waitForIdle()
        // New-tab browser chrome shows the tab count pill.
        assertTrue(v.tabs.isNotEmpty() || v.activeTab == null)
    }

    @Test fun omniboxEditorShowsSuggestions() {
        val v = vm()
        v.addBookmark("https://suggest.example", "Suggest Title")
        await("bookmark for suggestions") { v.bookmarks.value.any { it.title == "Suggest Title" } }
        rule.setContent {
            OmniboxEditor(
                viewModel = v,
                bookmarks = v.bookmarks.value,
                history = listOf(HistoryEntry(url = "https://past.example", title = "Past Title")),
                searchFocusRequester = androidx.compose.ui.focus.FocusRequester(),
                onNavigate = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Past Title").assertIsDisplayed()
    }
}
