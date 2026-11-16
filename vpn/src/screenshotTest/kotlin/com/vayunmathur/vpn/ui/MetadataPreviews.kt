package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.AppUsageSummary
import com.vayunmathur.vpn.data.DomainBytesSummary
import com.vayunmathur.vpn.data.DomainCountSummary
import com.vayunmathur.vpn.data.VpnConfig

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop/tablet window — comfortably above the 840dp expanded-width threshold. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Store listing images for `:vpn`. See `common-conventions-preview-metadata`.
 *
 * The pages take `(backStack, VpnViewModel)`, so the two screens shown here were split into
 * stateless [ConfigListContent] and [LoggingContent]. Navigation stays in the page: a
 * `NavBackStack` is not state the previews can meaningfully fake.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    /** Keys are fabricated but well-formed — WireGuard keys are 32 base64-encoded bytes. */
    private val tunnels = listOf(
        VpnConfig(
            id = 1,
            name = "Home",
            publicKey = "hM8Zq2Nw1fL0kR7bT4xVc9Yg6JpS3AeD5uWnQoIrK1U=",
            address = "10.7.0.2/32",
            dns = "10.7.0.1",
            peerPublicKey = "Xk4Lm9Pd2QsE7vRb0TnHc5YgW8ZaJf3UoI6xNqBr1S0=",
            peerAllowedIPs = "0.0.0.0/0, ::/0",
            peerEndpoint = "vpn.example.net:51820",
        ),
        VpnConfig(
            id = 2,
            name = "Frankfurt",
            publicKey = "Qw3Er5Ty7Ui9Op1As2Df4Gh6Jk8Lz0Xc1Vb3Nm5Q7E=",
            address = "10.66.14.9/32",
            dns = "1.1.1.1",
            peerPublicKey = "Zm2Xc4Vb6Nn8Aa0Ss1Dd3Ff5Gg7Hh9Jj1Kk3Ll5Zx7=",
            peerAllowedIPs = "0.0.0.0/0, ::/0",
            peerEndpoint = "185.220.101.47:51820",
        ),
        VpnConfig(
            id = 3,
            name = "Lab (split tunnel)",
            publicKey = "Pl0Ok9Ij8Uh7Yg6Tf5Rd4Es3Wa2Qz1Xs2Cd3Vf4Bg5=",
            address = "192.168.90.4/24",
            dns = "192.168.90.1",
            peerPublicKey = "Nb6Vc5Xz4Ls3Kj2Hg1Fd0Sa9Pq8Ow7Ie6Ru5Ty4Mn3=",
            peerAllowedIPs = "192.168.90.0/24",
            peerEndpoint = "lab.internal:51821",
        ),
    )

    private val topApps = listOf(
        AppUsageSummary("com.vayunmathur.youpipe", "YouPipe", 2_411_724_800, 4_812),
        AppUsageSummary("com.vayunmathur.web", "Web", 743_051_264, 12_904),
        AppUsageSummary("com.vayunmathur.maps", "Maps", 318_767_104, 2_216),
        AppUsageSummary("com.vayunmathur.communicate", "Communicate", 94_371_840, 8_431),
        AppUsageSummary("com.vayunmathur.email", "Email", 41_943_040, 1_507),
        AppUsageSummary("com.vayunmathur.appstore", "App Store", 12_582_912, 342),
    )

    private val domainsByCount = listOf(
        DomainCountSummary("googlevideo.com", 9_812),
        DomainCountSummary("duckduckgo.com", 4_207),
        DomainCountSummary("wikipedia.org", 1_933),
        DomainCountSummary("f-droid.org", 864),
        DomainCountSummary("tile.openstreetmap.org", 512),
    )

    private val domainsByBytes = listOf(
        DomainBytesSummary("googlevideo.com", 2_254_857_830),
        DomainBytesSummary("f-droid.org", 612_368_384),
        DomainBytesSummary("tile.openstreetmap.org", 208_666_624),
        DomainBytesSummary("wikipedia.org", 73_400_320),
        DomainBytesSummary("duckduckgo.com", 18_874_368),
    )

    @PreviewTest
    @Preview(name = "1-tunnels", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Tunnels() {
        ChromePreview(selectedTab = 0)
    }

    @PreviewTest
    @Preview(name = "2-top-apps", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2TopApps() {
        ChromePreview(selectedTab = 1)
    }

    @PreviewTest
    @Preview(name = "3-top-domains", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3TopDomains() {
        ChromePreview(selectedTab = 1, loggingTab = 2)
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    // CHROME EXEMPT: pushed traffic-detail route renders with no tab chrome on device.
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            // Expanded windows keep the tunnel list visible while the traffic
            // detail opens beside it instead of covering it. ConfigDetailPage
            // reads its ViewModel internally, so the previewable LoggingContent
            // stands in as the detail pane.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    ConfigListContent(
                        configs = tunnels,
                        connectingId = 1L,
                        activeId = 1L,
                    )
                }
                Box(Modifier.weight(0.6f).fillMaxHeight()) {
                    LoggingContent(
                        topApps = topApps,
                        domainsByCount = domainsByCount,
                        domainsByBytes = domainsByBytes,
                    )
                }
            }
        }
    }

    /**
     * The app's real tab chrome around preview screen content: the same
     * [TabbedPagerScaffold] with the same Tunnels/Logging/Settings tabs `VpnTabs`
     * declares. The Settings tab only needs a back stack (its actions fire intents
     * on click), so it renders for real; only the selected tab is literal.
     */
    @Composable
    private fun ChromePreview(selectedTab: Int, loggingTab: Int = 0) {
        DynamicTheme(darkTheme = true) {
            val startRoute: Route = when (selectedTab) {
                1 -> Route.Logging(loggingTab)
                2 -> Route.Settings
                else -> Route.Tunnels
            }
            val backStack = NavBackStack(arrayOf(startRoute))
            val tabs = listOf<RouteTab<Route>>(
                RouteTab(Route.Tunnels, "Tunnels") { IconDashboard() },
                RouteTab(Route.Logging(loggingTab), "Logging") { IconHistory() },
                RouteTab(Route.Settings, "Settings") { IconSettings() },
            )
            TabbedPagerScaffold(backStack = backStack, tabs = tabs) { route ->
                when (route) {
                    is Route.Logging -> LoggingContent(
                        topApps = topApps,
                        domainsByCount = domainsByCount,
                        domainsByBytes = domainsByBytes,
                        initialTab = loggingTab,
                    )
                    Route.Settings -> SettingsPage(backStack = backStack)
                    else -> ConfigListContent(
                        configs = tunnels,
                        connectingId = 1L,
                        activeId = 1L,
                    )
                }
            }
        }
    }
}
