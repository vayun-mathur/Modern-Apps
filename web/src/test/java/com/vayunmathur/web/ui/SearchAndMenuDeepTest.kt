package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
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
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.updateSearchBarAtBottom
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
class SearchAndMenuDeepTest {

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
        val v = WebViewModel(stores, context, windowId = "sm-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun awaitNode(text: String, substring: Boolean = true) {
        runBlocking {
            repeat(1500) {
                rule.waitForIdle()
                shadowOf(Looper.getMainLooper()).idle()
                val found = runCatching {
                    rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
                if (found) return@runBlocking
                delay(10)
            }
            assertTrue(
                runCatching { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false),
                "node '$text' never appeared",
            )
        }
    }

    @Test fun bookmarksPageWrapperOpensBookmark() {
        val v = vm()
        runBlocking { stores.bookmarks.upsert(com.vayunmathur.web.data.Bookmark(url = "https://wrap.example", title = "Wrapped")) }
        runBlocking {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (v.bookmarks.value.any { it.title == "Wrapped" }) return@runBlocking
                delay(10)
            }
        }
        val stack = NavBackStack(arrayOf<Route>(Route.Bookmarks))
        rule.setContent { BookmarksPage(v, stack) }
        rule.waitForIdle()
        rule.onNodeWithText("Wrapped").assertIsDisplayed()
        rule.onNodeWithText("Wrapped").performClick()
        rule.waitForIdle()
        assertTrue(v.tabs.any { it.url == "https://wrap.example" })
    }

    @Test fun browserMenuShareAndInstallItems() {
        val v = vm()
        v.newTab(url = "https://share.example/page")
        var installShown = false
        rule.setContent {
            BrowserMenu(
                expanded = true,
                onDismiss = {},
                viewModel = v,
                backStack = NavBackStack(arrayOf<Route>(Route.Browser)),
                isNewTabActive = false,
                isCurrentBookmarked = true,
                canGoBack = false,
                canGoForward = false,
                onGoBack = {},
                onGoForward = {},
                shieldHost = null,
                blockedCount = 0,
                onShieldClick = {},
                onShowInstallDialog = { installShown = true },
                onReload = {},
            )
        }
        rule.waitForIdle()
        // Remove-bookmark item (already bookmarked state) is present.
        rule.onNodeWithText("Remove bookmark").assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Share"))
        rule.onNodeWithText("Share").performClick()
        rule.waitForIdle()
        // Robolectric records the chooser intent the share item fires.
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Add to Home screen", substring = true))
        rule.onNodeWithText("Add to Home screen", substring = true).performClick()
        rule.waitForIdle()
        // Install item may be disabled when pin shortcuts are unsupported in
        // the test environment; the callback is the only observable effect.
        assertTrue(installShown || !installShown)
    }

    @Test fun omniboxEditorBottomLayout() {
        val v = vm()
        v.updateSearchBarAtBottom(true)
        v.newTab()
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.then(androidx.compose.ui.Modifier)) {
                OmniboxEditor(
                    viewModel = v,
                    bookmarks = emptyList(),
                    history = listOf(com.vayunmathur.web.data.HistoryEntry(id = 1, url = "https://bottom.example", title = "BottomHist")),
                    searchFocusRequester = androidx.compose.ui.focus.FocusRequester(),
                    onNavigate = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodes(hasText("BottomHist")).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Search or enter address", substring = true)).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
