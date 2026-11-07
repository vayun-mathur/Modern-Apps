package com.vayunmathur.vpn.ui

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.data.VpnConfigEntity
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PageCallbacksTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test fun configListPageDeleteRowAndStatusSnackbar() { runBlocking {
        val dao = VpnViewModelTest.FakeConfigDao()
        dao.rows.value = listOf(VpnConfigEntity(id = 3, name = "DelTun", peerEndpoint = "d.example:1"))
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                ConfigListPage(bs, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("DelTun")).fetchSemanticsNodes().isNotEmpty()
        }
        // Delete icon is the small icon button in the row (after Connect). Click the
        // narrow click-action nodes in the row area until the config disappears.
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        for ((i, n) in nodes.withIndex()) {
            if (dao.rows.value.isEmpty()) break
            if (n.boundsInRoot.width < 120 && n.boundsInRoot.top > 60) {
                runCatching { rule.onAllNodes(hasClickAction())[i].performClick() }
                rule.waitForIdle()
            }
        }
        withTimeout(8000) { while (dao.rows.value.isNotEmpty()) delay(50) }
        assertEquals(1, dao.deleted.size)
        // stopVpn sets the status synchronously; the page's snackbar effect consumes it
        vm.stopVpn()
        assertTrue(vm.status.value == "Disconnected" || vm.status.value == null, "status=${vm.status.value}")
        rule.waitForIdle()
        Unit
    } }

    // Note: row-card click -> Route.Detail navigation is asserted at the
    // ConfigListContent level in VpnComposeScreensTest (opened config). Driving the
    // same click through ConfigListPage's semantics tree here proved unreliable
    // (wide click-action nodes include scaffold containers that swallow the click
    // without reaching the row card), so the page-level lambda is left to that test
    // plus Navigation integration.

    // Note: ConfigListPage's "need Activity" snackbar branch cannot be composed in a
    // test: overriding LocalContext to a non-Activity makes rememberLauncherForActivityResult
    // throw (no ActivityResultRegistryOwner), so that defensive branch is unreachable
    // from any composition a test can build. Left uncovered deliberately.

    @Test fun upsertOnSavedFiresOnMain() { runBlocking {
        val dao = VpnViewModelTest.FakeConfigDao()
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        var saved = -1L
        vm.upsert(VpnConfig(name = "cb")) { saved = it }
        withTimeout(8000) {
            while (saved == -1L) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(50)
            }
        }
        assertTrue(saved > 0)
        Unit
    } }

    @Test fun importFromContentUriVariants() { runBlocking {
        val dao = VpnViewModelTest.FakeConfigDao()
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        val confText = "[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=\nEndpoint = content.example:51820\n"
        // content:// uri served by the shadow content resolver, name has no extension
        val uri = Uri.parse("content://com.example.docs/document/report")
        Shadows.shadowOf(app.contentResolver).registerInputStream(uri, confText.byteInputStream())
        vm.importFromUri(app, uri)
        withTimeout(8000) { while (dao.rows.value.isEmpty()) delay(50) }
        assertEquals("report", dao.rows.value.single().name)
        // An unregistered content uri is not exercised: Robolectric's UnregisteredInputStream
        // throws UnsupportedOperationException, which escapes the VM's catches and Robolectric
        // rethrows as a test failure. On a device that path is a FileNotFoundException and
        // lands in the VM's IOException catch ("Failed to read file").
        Unit
    } }
}
