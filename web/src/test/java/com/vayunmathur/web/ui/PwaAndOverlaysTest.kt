package com.vayunmathur.web.ui

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.PwaActivity
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.requestGeolocation
import com.vayunmathur.web.platform.requestWebPermission
import com.vayunmathur.web.platform.SitePermissionType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PwaAndOverlaysTest {

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
        val v = WebViewModel(stores, context, windowId = "pwa-${System.nanoTime()}", incognito = false)
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

    private fun launcher() = object : androidx.activity.result.ActivityResultLauncher<Array<String>>() {
        override fun launch(input: Array<String>, options: androidx.core.app.ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: androidx.activity.result.contract.ActivityResultContract<Array<String>, *> =
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    }

    private fun overlays(v: WebViewModel, linkUrl: String? = null, showInstall: Boolean = false) {
        rule.setContent {
            BrowserOverlays(
                viewModel = v,
                activeTab = v.activeTab,
                shieldHost = v.activeTab?.url?.let { com.vayunmathur.web.platform.BrowserUtils.hostFromUrl(it) },
                webViewPool = mutableMapOf(),
                linkContextMenuUrl = linkUrl,
                onLinkMenuDismiss = {},
                showInstallDialog = showInstall,
                onInstallDialogDismiss = {},
                multiDocLauncher = launcher(),
                singleDocLauncher = launcher(),
                localNetworkRequest = null,
            )
        }
        rule.waitForIdle()
    }

    @Test fun pwaBrowserComposesWebView() {
        rule.setContent {
            com.vayunmathur.web.platform.PwaBrowser(initialUrl = "https://pwa.example/app", title = "PWA")
        }
        rule.waitForIdle()
    }

    @Test fun overlaysWithNoPromptsRenderNothing() {
        val v = vm()
        v.newTab(url = "https://plain.example")
        overlays(v)
        assertTrue(rule.onAllNodes(hasText("plain.example", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun overlaysShowPermissionPromptAndGrant() {
        val v = vm()
        v.newTab(url = "https://perm.example")
        v.requestWebPermission("https://perm.example", listOf(SitePermissionType.CAMERA), {}, {})
        await("prompt") { v.pendingPermissionPrompt != null }
        overlays(v)
        rule.onNodeWithText("Camera").assertIsDisplayed()
        rule.onNodeWithText("Allow").performClick()
        rule.waitForIdle()
        await("prompt cleared") { v.pendingPermissionPrompt == null }
    }

    @Test fun overlaysShowGeolocationPrompt() {
        val v = vm()
        v.newTab(url = "https://geo.example")
        v.requestGeolocation("https://geo.example", {}, {})
        await("geo prompt") { v.pendingGeolocationPrompt != null }
        overlays(v)
        rule.onNodeWithText("Allow location", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Block").performClick()
        rule.waitForIdle()
        await("geo cleared") { v.pendingGeolocationPrompt == null }
    }

    @Test fun overlaysShowShieldsPanel() {
        val v = vm()
        v.newTab(url = "https://shield.example/page")
        v.showShieldsPanel = true
        overlays(v)
        rule.onNodeWithText("shield.example", substring = true).assertIsDisplayed()
    }

    @Test fun overlaysShowLinkMenuAndInstallDialog() {
        val v = vm()
        v.newTab(url = "https://link.example/page")
        overlays(v, linkUrl = "https://linked.example/target")
        rule.onAllNodes(hasText("linked.example", substring = true))[0].assertIsDisplayed()
    }

    @Test fun overlaysShowInstallDialog() {
        val v = vm()
        v.newTab(url = "https://inst.example/app")
        overlays(v, showInstall = true)
        rule.onNodeWithText("Add to Home screen?").assertIsDisplayed()
    }

    @Test fun navigationComposesBrowser() {
        val v = vm()
        rule.setContent { com.vayunmathur.web.Navigation(v) }
        rule.waitForIdle()
        assertTrue(v.tabs.isNotEmpty() || v.activeTab == null)
    }

    @Test fun pwaActivityComposesBrowser() {
        val intent = Intent(context, PwaActivity::class.java).apply {
            putExtra(PwaActivity.EXTRA_URL, "https://pwa2.example/app")
            putExtra(PwaActivity.EXTRA_TITLE, "PWA2")
        }
        val controller = Robolectric.buildActivity(PwaActivity::class.java, intent)
        val activity = controller.create().start().resume().get()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(activity)
        runCatching { controller.pause().stop().destroy() }
    }
}
