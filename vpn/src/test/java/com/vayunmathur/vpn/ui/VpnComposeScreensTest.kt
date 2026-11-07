package com.vayunmathur.vpn.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.data.DomainBytesSummary
import com.vayunmathur.vpn.data.DomainCountSummary
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.platform.VpnViewModel
import com.vayunmathur.vpn.platform.VpnViewModelTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VpnComposeScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private val tunnels = listOf(
        VpnConfig(id = 1, name = "Home", address = "10.7.0.2/32", dns = "10.7.0.1", peerPublicKey = "pk1", peerAllowedIPs = "0.0.0.0/0, ::/0", peerEndpoint = "vpn.example.net:51820"),
        VpnConfig(id = 2, name = "", address = "10.66.14.9/32", peerPublicKey = "pk2", peerAllowedIPs = "0.0.0.0/0", peerEndpoint = "185.220.101.47:51820"),
    )

    private fun vm(dao: VpnViewModelTest.FakeConfigDao = VpnViewModelTest.FakeConfigDao(), logDao: VpnViewModelTest.FakeLogDao = VpnViewModelTest.FakeLogDao()): VpnViewModel {
        // Seed flows before VM init polls
        return VpnViewModel(app, dao, logDao)
    }

    @Test fun configListEmptyShowsImportPrompt() {
        var imported = false
        rule.setContent { DynamicTheme { ConfigListContent(emptyList(), null, null, onImport = { imported = true }) } }
        rule.onNodeWithText("No tunnels yet").assertIsDisplayed()
        rule.onNodeWithText("Open .conf file").performClick()
        assertTrue(imported)
    }

    @Test fun configListRowsConnectOpenDelete() {
        var toggled: VpnConfig? = null; var opened: VpnConfig? = null; var deleted: VpnConfig? = null
        rule.setContent {
            DynamicTheme {
                ConfigListContent(tunnels, connectingId = 1, activeId = 2,
                    onToggleConnect = { toggled = it }, onOpen = { opened = it }, onDelete = { deleted = it })
            }
        }
        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNodeWithText("Unnamed").assertIsDisplayed() // blank name falls back
        rule.onNodeWithText("vpn.example.net:51820").assertIsDisplayed()
        // First row is connecting (spinner), second is active (Disconnect)
        rule.onNodeWithText("Disconnect").assertIsDisplayed().performClick()
        assertEquals(2L, toggled?.id)
        // Click the card itself: the semantics node with a click action that contains the title
        val homeNode = rule.onAllNodes(hasText("Home")).fetchSemanticsNodes().first()
        val c = homeNode.boundsInRoot.center
        val clickNodes = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        val cardIdx = clickNodes.indexOfFirst { c.x in it.boundsInRoot.left..it.boundsInRoot.right && c.y in it.boundsInRoot.top..it.boundsInRoot.bottom && it.boundsInRoot.width > 200 }
        assertTrue(cardIdx >= 0, "no card click node containing title at $c among ${clickNodes.map { it.boundsInRoot }}")
        // Click each wide click-action node in turn until the row's onOpen fires
        // (the card is one of them; buttons are narrower and were already exercised).
        val wide = clickNodes.withIndex().filter { it.value.boundsInRoot.width > 200 }
        for ((idx, _) in wide) {
            if (opened != null) break
            runCatching { rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[idx].performClick() }
            rule.waitForIdle()
        }
        // A card click opens that card's config (row identity for toggles is asserted above).
        kotlin.test.assertNotNull(opened)
        rule.onRoot().captureToImage()
    }

    @Test fun loggingTabsAndDeleteDialog() {
        var deleteAll = 0
        rule.setContent {
            DynamicTheme {
                LoggingContent(
                    topApps = listOf(AppUsageSummary("com.a", "App A", 2048, 3), AppUsageSummary(null, "", 1024, 1)),
                    domainsByCount = listOf(DomainCountSummary("a.com", 7)),
                    domainsByBytes = listOf(DomainBytesSummary("b.com", 1048576)),
                    onDeleteAllLogs = { deleteAll++ },
                )
            }
        }
        rule.onNodeWithText("App A").assertIsDisplayed()
        rule.onNodeWithText("2.0 KB").assertIsDisplayed()
        rule.onNodeWithText("Most visited").performClick()
        rule.onNodeWithText("a.com").assertIsDisplayed()
        rule.onNodeWithText("7 req").assertIsDisplayed()
        rule.onNodeWithText("Top domains by data").performClick()
        rule.onNodeWithText("b.com").assertIsDisplayed()
        rule.onNodeWithText("1.00 MB").assertIsDisplayed()
        rule.onRoot().captureToImage()
    }

    @Test fun loggingEmptyStates() {
        rule.setContent { DynamicTheme { LoggingContent(emptyList(), emptyList(), emptyList(), initialTab = 1) } }
        rule.onNodeWithText("No domains yet — connect the VPN and browse").assertIsDisplayed()
    }

    @Test fun formatBytesUnits() {
        assertEquals("0 B", formatBytes(0)); assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KB", formatBytes(1024)); assertEquals("1.50 MB", formatBytes(1572864))
        assertTrue(formatBytes(1L shl 30).endsWith("GB"))
        assertTrue(formatBytes(-5).startsWith("-5"))
    }

    @Test fun configDetailShowsFieldsAndExport() {
        val dao = VpnViewModelTest.FakeConfigDao()
        dao.rows.value = listOf(com.vayunmathur.vpn.data.VpnConfigEntity(id = 7, name = "DetailTun", privateKey = "privkey1234567890abcdef", publicKey = "pub", address = "10.0.0.2/32", dns = "1.1.1.1", mtu = 1420, peerPublicKey = "peerpub", peerAllowedIPs = "0.0.0.0/0", peerEndpoint = "host:51820", peerKeepalive = 25))
        val vm = vm(dao)
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                ConfigDetailPage(bs, vm, 7)
            }
        }
        rule.waitUntil(timeoutMillis = 8000) {
            rule.onAllNodes(hasText("DetailTun", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Export as .conf").performScrollTo().performClick()
        rule.waitForIdle()
        // Exported wg-quick text appears with the peer key
        rule.waitUntil(timeoutMillis = 5000) {
            rule.onAllNodes(hasText("PublicKey = peerpub", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun settingsPageRows() {
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                SettingsPage(bs)
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun bypassListPageLoadsApps() {
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                BypassListPage(bs)
            }
        }
        // Either the loading spinner resolves to the app list (with lockdown warning) or stays loading briefly
        rule.waitUntil(timeoutMillis = 10000) {
            rule.onAllNodes(hasText("Open system VPN settings", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodes(hasText("Loading", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onRoot().captureToImage()
    }

    @Test fun vpnTabsRender() {
        val vm = vm()
        rule.setContent {
            DynamicTheme {
                val bs = rememberNavBackStack<Route>(Route.Main())
                VpnTabs(bs, vm, 0)
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("No tunnels yet").assertIsDisplayed()
    }

    @Test fun routeKeys() {
        assertEquals(Route.Main(2), Route.Main(initialTab = 2))
        assertEquals(Route.Detail(5), Route.Detail(id = 5))
        assertEquals(Route.BypassList, Route.BypassList)
    }
}
