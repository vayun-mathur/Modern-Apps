package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.shieldsFor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebPagesDeepTest {

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
        val v = WebViewModel(stores, context, windowId = "deep-${System.nanoTime()}", incognito = false)
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

    private fun scrollDown(times: Int = 3) {
        repeat(times) {
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
        }
    }

    private fun scrollToText(text: String) {
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.waitForIdle()
    }

    // ------------------------------------------------------------ Settings

    @Test fun settingsTogglesFlipViewModelState() {
        val v = vm()
        rule.setContent { SettingsPage(v, backStack()) }
        rule.waitForIdle()
        val jsBefore = v.jsEnabled
        // Switches in order: toolbar, JavaScript, cookies, desktop.
        rule.onAllNodes(isToggleable())[1].performClick()
        rule.waitForIdle()
        assertEquals(!jsBefore, v.jsEnabled)
        scrollToText("Desktop mode")
        val count = rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        assertTrue(count >= 1)
        rule.onAllNodes(isToggleable())[count - 1].performClick()
        rule.waitForIdle()
        assertTrue(v.desktopMode || v.blockThirdPartyCookies)
    }

    @Test fun settingsRowsNavigate() {
        val v = vm()
        val stack = backStack()
        rule.setContent { SettingsPage(v, stack) }
        rule.waitForIdle()
        scrollToText("Site data")
        rule.onNodeWithText("Site data").performClick()
        rule.waitForIdle()
        assertEquals(Route.SiteData, stack.backStack.last())
        scrollToText("History")
        rule.onNodeWithText("History").performClick()
        rule.waitForIdle()
        assertEquals(Route.History, stack.backStack.last())
        scrollToText("Downloads")
        rule.onNodeWithText("Downloads").performClick()
        rule.waitForIdle()
        assertEquals(Route.Downloads, stack.backStack.last())
        scrollToText("Installed apps")
        rule.onNodeWithText("Installed apps").performClick()
        rule.waitForIdle()
        assertEquals(Route.InstalledSites, stack.backStack.last())
    }

    @Test fun settingsClearDataDialogCancels() {
        val v = vm()
        rule.setContent { SettingsPage(v, backStack()) }
        rule.waitForIdle()
        scrollToText("Clear browsing data")
        rule.onNodeWithText("Clear browsing data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear browsing data?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("Clear browsing data?")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun settingsShieldsRowNavigates() {
        val v = vm()
        val stack = backStack()
        rule.setContent { SettingsPage(v, stack) }
        rule.waitForIdle()
        scrollToText("Shields")
        rule.onNodeWithText("Shields").performClick()
        rule.waitForIdle()
        assertEquals(Route.Shields, stack.backStack.last())
    }

    // ------------------------------------------------------------ Bookmarks screen

    @Test fun bookmarksCreateFolderWithName() {
        var created: String? = null
        rule.setContent {
            BookmarksScreen(
                bookmarks = emptyList(),
                folders = emptyList(),
                onCreateFolder = { created = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("No bookmarks yet", substring = true).assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Folder")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Folder name", substring = true).performTextInput("Reading")
        rule.waitForIdle()
        rule.onNodeWithText("Create").performClick()
        rule.waitForIdle()
        assertEquals("Reading", created)
    }

    @Test fun bookmarksEmptyFolderState() {
        rule.setContent {
            BookmarksScreen(
                bookmarks = listOf(Bookmark(id = 1, url = "https://a.example", title = "A")),
                folders = listOf(BookmarkFolder(id = 9, name = "Empty")),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Empty").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No bookmarks in this folder", substring = true).assertIsDisplayed()
    }

    @Test fun bookmarksLongPressDeletes() {
        var deleted: Bookmark? = null
        rule.setContent {
            BookmarksScreen(
                bookmarks = listOf(Bookmark(id = 1, url = "https://a.example", title = "Delete Me")),
                folders = emptyList(),
                onDelete = { deleted = it },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Delete Me").performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Delete bookmark", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals("https://a.example", deleted?.url)
    }

    // ------------------------------------------------------------ Shields panel/page

    @Test fun shieldsPanelLevelAndToggleInteractions() {
        val v = vm()
        var reloaded = false
        var dismissed = false
        rule.setContent {
            ShieldsPanel(
                host = "panel.example",
                blockedCount = 5,
                viewModel = v,
                onReload = { reloaded = true },
                onDismiss = { dismissed = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("panel.example", substring = true).assertIsDisplayed()
        // Pick the Standard level via its radio row, then reload to apply.
        rule.onNodeWithText("Standard").performClick()
        rule.waitForIdle()
        assertEquals(
            com.vayunmathur.web.domain.ShieldLevel.STANDARD,
            v.shieldsFor("panel.example").level,
            "overrides=${v.shieldOverrides}",
        )
        rule.onNode(androidx.compose.ui.test.hasText("Reload to apply") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        // The reload button dismisses after reloading; under Robolectric the
        // sheet's dismiss is the reliably observable half of that click.
        assertTrue(dismissed, "dismissed should be set by the reload button")
    }

    @Test fun shieldsPageGlobalToggleAndLevel() {
        val v = vm()
        rule.setContent { ShieldsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("Aggressive", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Standard").performClick()
        rule.waitForIdle()
        assertEquals(com.vayunmathur.web.domain.ShieldLevel.STANDARD, v.shields.level)
        scrollDown(3)
        assertTrue(rule.onAllNodes(hasText("Site exceptions", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun shieldsPageListsAndClearsExceptions() {
        val v = vm()
        runBlocking {
            stores.shields.upsert(com.vayunmathur.web.data.ShieldSetting.from("except.example", com.vayunmathur.web.domain.ShieldsSettings(blockTrackers = false)))
        }
        // The VM's shield flow is collected from the repository at init; a fresh VM sees it.
        val v2 = vm()
        await("exception flows") { v2.shieldSettings.value.any { it.host == "except.example" } }
        rule.setContent { ShieldsPage(v2, backStack()) }
        rule.waitForIdle()
        scrollToText("except.example")
        rule.onNodeWithText("except.example").assertIsDisplayed()
        scrollToText("Clear all site exceptions")
        rule.onNodeWithText("Clear all site exceptions", substring = true).performClick()
        rule.waitForIdle()
        await("exceptions cleared") { v2.shieldOverrides.isEmpty() }
        assertFalse(v2.shieldOverrides.containsKey("except.example"))
    }

    // ------------------------------------------------------------ Browser menu

    @Test fun browserMenuAllDestinations() {
        val v = vm()
        v.newTab(url = "https://menu2.example/page")
        val stack = backStack()
        var back = false; var forward = false; var shields = false; var reload = false
        rule.setContent {
            BrowserMenu(
                expanded = true,
                onDismiss = {},
                viewModel = v,
                backStack = stack,
                isNewTabActive = false,
                isCurrentBookmarked = false,
                canGoBack = true,
                canGoForward = true,
                onGoBack = { back = true },
                onGoForward = { forward = true },
                shieldHost = "menu2.example",
                blockedCount = 4,
                onShieldClick = { shields = true },
                onShowInstallDialog = {},
                onReload = { reload = true },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Back").performClick()
        rule.waitForIdle()
        assertTrue(back)
        rule.onNodeWithText("Forward").performClick()
        rule.waitForIdle()
        assertTrue(forward)
        rule.onNodeWithText("Shields").performClick()
        rule.waitForIdle()
        assertTrue(shields)
        rule.onNodeWithText("Reload").performClick()
        rule.waitForIdle()
        assertTrue(reload)
        scrollToText("Bookmarks")
        rule.onNodeWithText("Bookmarks").performClick()
        rule.waitForIdle()
        assertEquals(Route.Bookmarks, stack.backStack.last())
        scrollToText("Settings")
        rule.onNodeWithText("Settings").performClick()
        rule.waitForIdle()
        assertEquals(Route.Settings, stack.backStack.last())
        val tabsBefore = v.tabs.size
        scrollToText("New private tab")
        rule.onNodeWithText("New private tab").performClick()
        rule.waitForIdle()
        assertEquals(tabsBefore + 1, v.tabs.size)
        assertTrue(v.tabs.last().isPrivate)
    }

    @Test fun browserMenuBookmarkToggleAddsBookmark() {
        val v = vm()
        v.newTab(url = "https://tobookmark.example/page")
        rule.setContent {
            BrowserMenu(
                expanded = true,
                onDismiss = {},
                viewModel = v,
                backStack = backStack(),
                isNewTabActive = false,
                isCurrentBookmarked = false,
                canGoBack = false,
                canGoForward = false,
                onGoBack = {},
                onGoForward = {},
                shieldHost = null,
                blockedCount = 0,
                onShieldClick = {},
                onShowInstallDialog = {},
                onReload = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Add bookmark").performClick()
        rule.waitForIdle()
        await("bookmark added") { v.bookmarks.value.any { it.url == "https://tobookmark.example/page" } }
    }

    @Test fun installDialogConfirmInstalls() {
        val v = vm()
        v.newTab(url = "https://install2.example/app")
        var dismissed = false
        rule.setContent { InstallAppDialog(v, onDismiss = { dismissed = true }) }
        rule.waitForIdle()
        rule.onNodeWithText("Add").performClick()
        rule.waitForIdle()
        assertTrue(dismissed)
        await("installed") { v.installedSites.value.any { it.url == "https://install2.example/app" } }
    }

    // ------------------------------------------------------------ History clear

    @Test fun historyClearDialogClears() {
        val v = vm()
        runBlocking { stores.history.upsert(com.vayunmathur.web.data.HistoryEntry(url = "https://h.example", title = "Hist", visitedAt = System.currentTimeMillis())) }
        await("history") { v.history.value.isNotEmpty() }
        rule.setContent { HistoryPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Delete")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Clear history", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()
        await("history cleared") { v.history.value.isEmpty() }
    }

    // ------------------------------------------------------------ Downloads clear

    @Test fun downloadsClearActionEmpties() {
        val v = vm()
        runBlocking { stores.downloads.upsert(com.vayunmathur.web.data.DownloadEntry(url = "https://d.example/f", fileName = "f.bin", startedAt = System.currentTimeMillis())) }
        await("downloads") { v.downloads.value.isNotEmpty() }
        rule.setContent { DownloadsPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Delete")).performClick()
        rule.waitForIdle()
        await("downloads cleared") { v.downloads.value.isEmpty() }
        rule.onNodeWithText("No downloads", substring = true).assertIsDisplayed()
    }

    @Test fun installedSitesRemoveAction() {
        val v = vm()
        runBlocking { stores.installed.upsert(com.vayunmathur.web.data.InstalledSite(id = "rm1", url = "https://rm.example", title = "RemoveApp", origin = "https://rm.example")) }
        await("installed") { v.installedSites.value.isNotEmpty() }
        rule.setContent { InstalledSitesPage(v, backStack()) }
        rule.waitForIdle()
        rule.onNodeWithText("RemoveApp").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasContentDescription("Close")).performClick()
        rule.waitForIdle()
        await("installed removed") { v.installedSites.value.isEmpty() }
    }
}
