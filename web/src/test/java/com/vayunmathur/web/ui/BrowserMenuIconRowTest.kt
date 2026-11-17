package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BrowserMenuIconRowTest {

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
        db.close()
    }

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "menu-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun setMenu(
        v: WebViewModel,
        stack: NavBackStack<Route> = NavBackStack(arrayOf<Route>(Route.Browser)),
        isNewTabActive: Boolean = false,
        canGoBack: Boolean = true,
        canGoForward: Boolean = true,
        onGoBack: () -> Unit = {},
        onGoForward: () -> Unit = {},
        onReload: () -> Unit = {},
    ) {
        rule.setContent {
            BrowserMenu(
                expanded = true,
                onDismiss = {},
                viewModel = v,
                backStack = stack,
                isNewTabActive = isNewTabActive,
                isCurrentBookmarked = false,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onGoBack = onGoBack,
                onGoForward = onGoForward,
                shieldHost = null,
                blockedCount = 0,
                onShieldClick = {},
                onShowInstallDialog = {},
                onReload = onReload,
            )
        }
        rule.waitForIdle()
    }

    @Test fun iconRowHasFiveActionsInOrderAndNoDuplicateTextRows() {
        val v = vm()
        v.newTab(url = "https://menu.example/page")
        setMenu(v)
        val descriptions = listOf("Back", "Forward", "Refresh", "Share", "History")
        val lefts = descriptions.map { desc ->
            rule.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot.left
        }
        // One row, left to right in the required order.
        assertEquals(lefts.sorted(), lefts)
        assertEquals(descriptions.size, lefts.toSet().size)
        // The same actions no longer exist as text rows underneath.
        for (label in listOf("Back", "Forward", "Reload", "Share", "History")) {
            assertTrue(
                rule.onAllNodes(hasText(label, substring = false)).fetchSemanticsNodes().isEmpty(),
                "duplicate text row still present: $label",
            )
        }
        // Remaining text destinations are still there.
        assertTrue(rule.onAllNodes(hasText("New tab", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasText("Bookmarks", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun iconRowDisabledStatesGreyOutAndDoNotFire() {
        val v = vm()
        v.newTab(url = "https://menu.example/page")
        var backFired = false
        var reloadFired = false
        setMenu(
            v,
            isNewTabActive = true,
            canGoBack = false,
            canGoForward = false,
            onGoBack = { backFired = true },
            onReload = { reloadFired = true },
        )
        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Forward").assertIsNotEnabled()
        // Nothing to reload on a new tab, so reload is disabled in place.
        rule.onNodeWithContentDescription("Refresh").assertIsNotEnabled()
        // Share and History stay available.
        rule.onNodeWithContentDescription("Share").assertIsEnabled()
        rule.onNodeWithContentDescription("History").assertIsEnabled()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Refresh").performClick()
        rule.waitForIdle()
        assertTrue(!backFired && !reloadFired)
    }

    @Test fun iconRowActionsFireAndHistoryNavigates() {
        val v = vm()
        v.newTab(url = "https://menu.example/page")
        val stack = NavBackStack(arrayOf<Route>(Route.Browser))
        var back = false
        var forward = false
        var reload = false
        setMenu(
            v,
            stack = stack,
            onGoBack = { back = true },
            onGoForward = { forward = true },
            onReload = { reload = true },
        )
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Forward").performClick()
        rule.onNodeWithContentDescription("Refresh").performClick()
        rule.waitForIdle()
        assertTrue(back && forward && reload)
        rule.onNodeWithContentDescription("History").performClick()
        rule.waitForIdle()
        assertEquals(Route.History, stack.backStack.lastOrNull())
    }

    @Test fun shareIconSharesActiveTabUrl() {
        val v = vm()
        v.newTab(url = "https://menu.example/share-me")
        setMenu(v)
        rule.onNodeWithContentDescription("Share").performClick()
        rule.waitForIdle()
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
    }
}
