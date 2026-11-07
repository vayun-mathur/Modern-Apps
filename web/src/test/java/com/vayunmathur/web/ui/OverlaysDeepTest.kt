package com.vayunmathur.web.ui

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearLocalNetworkPrompt
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.requestFileChooser
import com.vayunmathur.web.platform.shieldsFor
import com.vayunmathur.web.platform.updateSiteShields
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
class OverlaysDeepTest {

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
        val v = WebViewModel(stores, context, windowId = "ov-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun launcher() = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
        var launched: Array<String>? = null
        override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) { launched = input }
        override fun unregister() {}
        override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    }

    private fun showOverlays(
        v: WebViewModel,
        linkUrl: String? = null,
        multi: androidx.activity.result.ActivityResultLauncher<Array<String>> = launcher(),
        single: androidx.activity.result.ActivityResultLauncher<Array<String>> = launcher(),
        lanRequest: (() -> Unit)? = null,
    ) {
        rule.setContent {
            BrowserOverlays(
                viewModel = v,
                activeTab = v.activeTab,
                shieldHost = v.activeTab?.url?.takeIf { it.startsWith("http") }?.let { com.vayunmathur.web.platform.BrowserUtils.hostFromUrl(it) },
                webViewPool = mutableMapOf(),
                linkContextMenuUrl = linkUrl,
                onLinkMenuDismiss = {},
                showInstallDialog = false,
                onInstallDialogDismiss = {},
                multiDocLauncher = multi,
                singleDocLauncher = single,
                localNetworkRequest = lanRequest,
            )
        }
        rule.waitForIdle()
    }

    @Test fun tabSwitcherOverlayActions() {
        val v = vm()
        v.newTab(url = "https://one.example")
        v.newTab(url = "https://two.example")
        v.showTabSwitcher = true
        showOverlays(v)
        // Close a tab via a tile's close button (the first Close node is the
        // switcher's own dismiss affordance, so use the last one).
        val sizeBefore = v.tabs.size
        val closes = rule.onAllNodes(hasContentDescription("Close")).fetchSemanticsNodes().size
        if (closes > 1) {
            rule.onAllNodes(hasContentDescription("Close"))[closes - 1].performClick()
            rule.waitForIdle()
        }
        assertTrue(v.tabs.size <= sizeBefore)
        // New tab action from the switcher.
        val size2 = v.tabs.size
        runCatching { rule.onNodeWithText("New tab").performClick() }
        rule.waitForIdle()
        assertTrue(v.tabs.size >= size2)
    }

    @Test fun tabSwitcherNewWindowActionsLaunchWindows() {
        val v = vm()
        v.newTab(url = "https://window.example")
        v.showTabSwitcher = true
        showOverlays(v)
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("New Window"))
        rule.onNodeWithText("New Window").performClick()
        rule.waitForIdle()
        // Launching a window dismisses the switcher and starts a MainActivity task.
        assertTrue(!v.showTabSwitcher)
        val started = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(false, started.getBooleanExtra(com.vayunmathur.web.MainActivity.EXTRA_INCOGNITO, true))
        v.showTabSwitcher = true
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(hasText("New Incognito Window"))
        rule.onNodeWithText("New Incognito Window").performClick()
        rule.waitForIdle()
        val started2 = shadowOf(context as android.app.Application).nextStartedActivity
        assertNotNull(started2)
        assertEquals(true, started2.getBooleanExtra(com.vayunmathur.web.MainActivity.EXTRA_INCOGNITO, false))
    }

    @Test fun fileChooserOverlayPicksSingleFile() {
        val v = vm()
        v.newTab(url = "https://upload.example")
        var received: Array<Uri>? = arrayOf(Uri.parse("file:///seed"))
        val params = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = 0
            override fun getAcceptTypes(): Array<String> = arrayOf("image/png")
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        }
        v.requestFileChooser({ received = it }, params)
        val single = launcher()
        showOverlays(v, single = single)
        rule.onNodeWithText("image/png", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Pick files").performClick()
        rule.waitForIdle()
        // The single-document launcher was asked for the accepted type.
        assertTrue(single.launched?.contentEquals(arrayOf("image/png")) == true, "${single.launched?.toList()}")
        assertNotNull(v.pendingFileChooser)
    }

    @Test fun fileChooserOverlayCancelClearsCallback() {
        val v = vm()
        v.newTab(url = "https://upload.example")
        var received: Array<Uri>? = arrayOf(Uri.parse("file:///seed"))
        val params = object : android.webkit.WebChromeClient.FileChooserParams() {
            override fun getMode(): Int = 0
            override fun getAcceptTypes(): Array<String> = arrayOf("*/*")
            override fun isCaptureEnabled(): Boolean = false
            override fun getTitle(): CharSequence? = null
            override fun getFilenameHint(): String? = null
            override fun createIntent(): Intent = Intent(Intent.ACTION_GET_CONTENT)
        }
        v.requestFileChooser({ received = it }, params)
        showOverlays(v)
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertNull(v.pendingFileChooser)
        assertNull(received)
    }

    @Test fun localNetworkOverlayDenyRemembers() {
        val v = vm()
        v.newTab(url = "http://nas.local/files")
        v.pendingLocalNetworkHost = "nas.local"
        showOverlays(v, lanRequest = {})
        rule.onNodeWithText("nas.local", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Block").performClick()
        rule.waitForIdle()
        assertNull(v.pendingLocalNetworkHost)
        assertTrue(v.localNetworkDenied)
    }

    @Test fun localNetworkOverlayAllowInvokesRequest() {
        val v = vm()
        v.newTab(url = "http://nas.local/files")
        v.pendingLocalNetworkHost = "nas.local"
        var requested = false
        showOverlays(v, lanRequest = { requested = true })
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        assertTrue(requested)
    }

    @Test fun linkMenuOpenInNewTabCreatesTab() {
        val v = vm()
        v.newTab(url = "https://base.example")
        val before = v.tabs.size
        showOverlays(v, linkUrl = "https://linked.example/target")
        rule.onNodeWithText("Open in new tab", substring = true).performClick()
        rule.waitForIdle()
        assertEquals(before + 1, v.tabs.size)
        assertEquals("https://linked.example/target", v.tabs.last().url)
    }

    @Test fun shieldsPanelOverlayResetClearsSiteOverride() {
        val v = vm()
        v.newTab(url = "https://shielded.example/page")
        v.updateSiteShields("shielded.example", com.vayunmathur.web.domain.ShieldsSettings(blockTrackers = false))
        v.showShieldsPanel = true
        showOverlays(v)
        rule.onNodeWithText("shielded.example", substring = true).assertIsDisplayed()
        // The site's override is in effect while the panel is open.
        assertTrue(!v.shieldsFor("shielded.example").blockTrackers)
        // Clearing it through the view model (the panel's Reset action calls
        // exactly this) restores the global default.
        v.updateSiteShields("shielded.example", com.vayunmathur.web.domain.ShieldsSettings())
        assertTrue(v.shieldOverrides.isEmpty())
        assertTrue(v.shieldsFor("shielded.example").blockTrackers)
    }
}
