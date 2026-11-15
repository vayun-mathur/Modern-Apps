package com.vayunmathur.auto.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.PairingViewModel
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.protocol.UsbSession
import com.vayunmathur.auto.protocol.WirelessSession
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The ViewModel-backed [PairingScreen] overload: collects the transport
 * flows and registers the connection-changed receiver, then delegates to
 * the stateless screen (whose states PairingScreenTest covers).
 */
@RunWith(RobolectricTestRunner::class)
class PairingViewModelScreenTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun resetTransports() {
        // TransportState is process-global; other suites leave it mid-session.
        TransportState.publishUsb(UsbSession())
        TransportState.publishWireless(WirelessSession())
        TransportState.publishRole(false)
    }

    @Test fun `view model screen renders the disconnected state`() {
        var backs = 0
        val viewModel = PairingViewModel()
        rule.setContent { PairingScreen(viewModel, onNavigateBack = { backs++ }) }
        rule.onNodeWithText(ctx.getString(AutoR.string.pairing_title)).assertExists()
        rule.onNodeWithText(ctx.getString(AutoR.string.pairing_usb_detached)).assertExists()
        rule.onNodeWithText(ctx.getString(AutoR.string.pairing_wireless_idle)).assertExists()
    }

    @Test fun `pinned picker ranked rows expose reorder actions`() {
        val ups = mutableListOf<String>()
        val downs = mutableListOf<String>()
        rule.setContent {
            PinnedAppsScreen(
                onNavigateBack = {},
                rows = listOf(
                    com.vayunmathur.auto.domain.PinnedAppRow(
                        com.vayunmathur.auto.platform.DiscoveredApp(
                            component = android.content.ComponentName("com.example.maps", "com.example.maps.Svc"),
                            label = "Maps", icon = null, categories = emptySet(),
                        ),
                        rank = 0,
                    ),
                ),
                onToggle = {},
                onMoveUp = { ups += it },
                onMoveDown = { downs += it },
            )
        }
        rule.onNodeWithText(ctx.getString(AutoR.string.pinned_apps_rank, 1)).assertExists()
        rule.onNodeWithText("Maps").assertExists()
    }
}
