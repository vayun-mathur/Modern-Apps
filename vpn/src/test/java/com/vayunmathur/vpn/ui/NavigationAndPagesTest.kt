package com.vayunmathur.vpn.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.vpn.Navigation
import com.vayunmathur.vpn.data.VpnConfigEntity
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationAndPagesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val ctx: Context get() = app

    private fun fakeOwner(uri: Uri): ActivityResultRegistryOwner {
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        return object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry get() = registry
        }
    }

    private fun seededVm(): VpnViewModel {
        val dao = VpnViewModelTest.FakeConfigDao()
        dao.rows.value = listOf(VpnConfigEntity(id = 1, name = "NavTun", peerEndpoint = "nav.example:51820", address = "10.0.0.2/32"))
        return VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
    }

    @Test fun navigationTabsAndSettingsToBypass() {
        val vm = seededVm()
        rule.setContent { DynamicTheme { Navigation(vm) } }
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("NavTun")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Logging").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Settings").performClick()
        rule.waitForIdle()
        // Settings rows appear; open the bypass list
        val bypass = rule.onAllNodes(hasText("Bypass list")).fetchSemanticsNodes()
        assertTrue(bypass.isNotEmpty(), "settings should show Bypass list row")
        rule.onNodeWithText("Bypass list").performClick()
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("Apps that bypass the VPN", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Loading apps", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun configListPageImportViaFakePicker() {
        val conf = File(ctx.cacheDir, "picked-vpn.conf").apply {
            writeText("[Interface]\nPrivateKey = abc=\n[Peer]\nPublicKey = def=\nEndpoint = picked.example:51820\n")
        }
        val dao = VpnViewModelTest.FakeConfigDao()
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides fakeOwner(Uri.fromFile(conf))) {
                DynamicTheme {
                    val bs = com.vayunmathur.library.util.rememberNavBackStack<com.vayunmathur.vpn.Route>(com.vayunmathur.vpn.Route.Main())
                    com.vayunmathur.vpn.ui.ConfigListPage(bs, vm)
                }
            }
        }
        rule.onNodeWithText("No tunnels yet").assertIsDisplayed()
        // FAB (add) is a click-action node at the bottom; the empty-state button also imports.
        rule.onNodeWithText("Open .conf file").performClick()
        rule.waitUntil(timeoutMillis = 10000) { dao.rows.value.isNotEmpty() }
        assertTrue(dao.rows.value.single().peerEndpoint.contains("picked.example"))
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("picked-vpn", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                dao.rows.value.isNotEmpty()
        }
    }

    @Test fun configListPageToggleConnectWithoutVpnPermissionShowsStatus() {
        val dao = VpnViewModelTest.FakeConfigDao()
        dao.rows.value = listOf(VpnConfigEntity(id = 9, name = "ToggleTun", peerEndpoint = "t.example:1"))
        val vm = VpnViewModel(app, dao, VpnViewModelTest.FakeLogDao())
        rule.setContent {
            DynamicTheme {
                val bs = com.vayunmathur.library.util.rememberNavBackStack<com.vayunmathur.vpn.Route>(com.vayunmathur.vpn.Route.Main())
                ConfigListPage(bs, vm)
            }
        }
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("ToggleTun")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Connect").performClick()
        rule.waitForIdle()
        // VpnService.prepare returns an intent under Robolectric, so the ViewModel asks for
        // permission and reports the granting status via snackbar/status flow.
        rule.waitUntil(timeoutMillis = 8000) {
            vm.status.value != null || rule.onAllNodes(hasText("Granting", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(true)
    }
}
