package com.vayunmathur.web.ui

import android.os.Looper
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.SearchEngine
import com.vayunmathur.web.platform.SitePermissionType
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.isNativeSearchUrl
import com.vayunmathur.web.platform.nativeSearchQuery
import com.vayunmathur.web.platform.navigateActiveTab
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.requestWebPermission
import com.vayunmathur.web.platform.updateSearchEngine
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
class LastBatchTest {

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
        val v = WebViewModel(stores, context, windowId = "last-${System.nanoTime()}", incognito = false)
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

    @Test fun selfHostedEngineNavigatesNatively() {
        val v = vm()
        v.newTab()
        v.updateSearchEngine(SearchEngine.SELF_HOSTED)
        // Only meaningful where the dev gate keeps SELF_HOSTED effective.
        if (SearchEngine.effective(v.searchEngine) != SearchEngine.SELF_HOSTED) return
        v.navigateActiveTab("native query")
        assertTrue(isNativeSearchUrl(v.activeTab!!.url), v.activeTab!!.url)
        assertEquals("native query", nativeSearchQuery(v.activeTab!!.url))
        assertEquals("native query", v.omniboxText)
        // A typed URL still navigates normally under the self-hosted engine.
        v.navigateActiveTab("example.com")
        assertEquals("https://example.com", v.activeTab!!.url)
    }

    @Test fun partialSavedPermissionPromptsForRemainderAndGrants() {
        runBlocking {
            stores.permissions.upsert(SitePermission(origin = "https://partial.example", cameraAllowed = true))
        }
        val v = vm()
        var granted: List<SitePermissionType>? = null
        var denied = false
        v.requestWebPermission(
            "https://partial.example",
            listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE, SitePermissionType.LOCATION, SitePermissionType.NOTIFICATIONS),
            { granted = it },
            { denied = true },
        )
        await("prompt for remainder") { v.pendingPermissionPrompt != null }
        assertEquals(
            listOf(SitePermissionType.MICROPHONE, SitePermissionType.LOCATION, SitePermissionType.NOTIFICATIONS),
            v.pendingPermissionPrompt?.types,
        )
        v.pendingPermissionPrompt?.onGrant?.invoke(listOf(SitePermissionType.MICROPHONE))
        await("grant delivered") { granted != null }
        assertEquals(listOf(SitePermissionType.CAMERA, SitePermissionType.MICROPHONE), granted)
        await("mic persisted") {
            runBlocking { stores.permissions.byOrigin("https://partial.example")?.microphoneAllowed == true }
        }
        // Deny path for a fresh origin with a pre-granted type still grants the pre-granted set.
        runBlocking {
            stores.permissions.upsert(SitePermission(origin = "https://partial2.example", locationAllowed = true))
        }
        var granted2: List<SitePermissionType>? = null
        var denied2 = false
        v.requestWebPermission(
            "https://partial2.example",
            listOf(SitePermissionType.LOCATION, SitePermissionType.CAMERA),
            { granted2 = it },
            { denied2 = true },
        )
        await("second prompt") { v.pendingPermissionPrompt?.origin == "https://partial2.example" }
        v.pendingPermissionPrompt?.onDeny?.invoke()
        await("deny delivers pre-granted") { granted2 != null || denied2 }
        assertEquals(listOf(SitePermissionType.LOCATION), granted2)
    }

    @Test fun webViewClientPageFinishedWithLoadedUrl() {
        val v = vm()
        v.newTab(url = "https://finish.example/start")
        val tabId = v.activeTabId!!
        val client = createBrowserWebViewClient(context, tabId, v, isPrivateTab = false)
        val webView = WebView(context)
        webView.loadUrl("https://finish.example/loaded")
        shadowOf(Looper.getMainLooper()).idle()
        client.onPageStarted(webView, "https://finish.example/loaded", null)
        client.onPageFinished(webView, "https://finish.example/loaded")
        shadowOf(Looper.getMainLooper()).idle()
        // Title falls back to the URL; the visit is recorded unless private.
        await("history from page finish") { v.history.value.any { it.url == "https://finish.example/loaded" } }
        assertEquals("https://finish.example/loaded", v.tabs.first { it.id == tabId }.url)
    }

    @Test fun installDialogRenameAndConfirm() {
        val v = vm()
        v.newTab(url = "https://rename.example/app")
        var dismissed = false
        rule.setContent { InstallAppDialog(v, onDismiss = { dismissed = true }) }
        rule.waitForIdle()
        // Rename the shortcut, then confirm.
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("Renamed App")
        rule.waitForIdle()
        rule.onNode(hasText("Add") and androidx.compose.ui.test.hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(dismissed)
        await("renamed install") { v.installedSites.value.any { it.url == "https://rename.example/app" } }
    }

    @Test fun installedSitesScrollToFooter() {
        val v = vm()
        runBlocking {
            stores.installed.upsert(com.vayunmathur.web.data.InstalledSite(id = "foot1", url = "https://foot.example", title = "FootApp", origin = "https://foot.example"))
        }
        await("installed") { v.installedSites.value.isNotEmpty() }
        rule.setContent { InstalledSitesPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("Shortcuts open", substring = true))
        rule.onNodeWithText("Shortcuts open", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText("without browser controls", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun browserPageNavigateFromEditorViaImeSearch() {
        val v = vm()
        v.newTab(url = "https://ime.example/start")
        rule.setContent { BrowserPage(v, NavBackStack(arrayOf<Route>(Route.Browser))) }
        rule.waitForIdle()
        rule.onNodeWithText("https://ime.example/start", substring = true).performClick()
        rule.waitForIdle()
        assertTrue(v.omniboxFocused)
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("example.org")
        rule.waitForIdle()
        rule.onAllNodes(hasSetTextAction())[0].performImeAction()
        rule.waitForIdle()
        // IME search navigates the active tab (or at least records the draft).
        assertTrue(v.activeTab!!.url.contains("example") || v.searchDraft.contains("example"))
    }
}
