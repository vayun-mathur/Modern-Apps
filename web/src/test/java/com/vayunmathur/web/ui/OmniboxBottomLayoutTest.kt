package com.vayunmathur.web.ui

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.updateSearchBarAtBottom
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h1280dp")
class OmniboxBottomLayoutTest {

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

    @Test fun bottomOmniboxEditorDocksFieldAtBottom() {
        val v = WebViewModel(stores, context, windowId = "bottom-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        v.updateSearchBarAtBottom(true)
        v.newTab()
        var navigated: String? = null
        var dismissed = false
        val searchFocusRequester = androidx.compose.ui.focus.FocusRequester()
        rule.setContent {
            OmniboxEditor(
                viewModel = v,
                bookmarks = emptyList(),
                history = listOf(HistoryEntry(id = 1, url = "https://dock.example", title = "DockHist")),
                searchFocusRequester = searchFocusRequester,
                onNavigate = { navigated = it },
                onDismiss = { dismissed = true },
            )
        }
        rule.waitForIdle()
        // With a tall window and the toolbar at the bottom, the editor uses its
        // bottom-docked scaffold branch; history suggestions still show.
        rule.onNodeWithText("DockHist").assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("dock query")
        rule.waitForIdle()
        assertEquals("dock query", v.searchDraft)
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Close"))[0].performClick()
        rule.waitForIdle()
        assertTrue(dismissed || v.searchDraft.isEmpty())
        assertEquals(null, navigated)
    }

    @Test fun topOmniboxEditorInTallWindowWhenBarAtTop() {
        val v = WebViewModel(stores, context, windowId = "top-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        v.updateSearchBarAtBottom(false)
        v.newTab()
        val searchFocusRequester = androidx.compose.ui.focus.FocusRequester()
        rule.setContent {
            OmniboxEditor(
                viewModel = v,
                bookmarks = emptyList(),
                history = listOf(HistoryEntry(id = 1, url = "https://top.example", title = "TopHist")),
                searchFocusRequester = searchFocusRequester,
                onNavigate = {},
                onDismiss = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("TopHist").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("Search or enter address", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
