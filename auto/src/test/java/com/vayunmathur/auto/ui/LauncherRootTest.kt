package com.vayunmathur.auto.ui

import android.content.ComponentName
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.CarLauncherActions
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.DiscoveredApp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class LauncherRootTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun app(pkg: String, label: String) = DiscoveredApp(
        ComponentName(pkg, "$pkg.Main"), label, null, setOf("nav"),
    )
    private val maps = app("com.example.maps", "Maps")
    private val music = app("com.example.music", "Music")

    @BeforeTest fun reset() {
        CarLauncherState.selectApp(null)
        CarLauncherState.setDiscovered(emptyList())
        CarLauncherState.setPinnedOrder(emptyList())
        CarLauncherState.updateActions { CarLauncherActions() }
    }

    @Test fun `home shows grid and tapping an app selects it`() {
        CarLauncherState.setDiscovered(listOf(maps, music))
        rule.setContent { LauncherRoot() }
        rule.onNodeWithText("Maps").assertExists()
        rule.onNodeWithText("Music").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Maps"))[0].performClick()
        assertEquals(maps.id, CarLauncherState.selectedApp.value)
    }

    @Test fun `empty home shows no-apps label`() {
        rule.setContent { LauncherRoot() }
        rule.onNodeWithText(ctx.getString(AutoR.string.car_no_apps)).assertExists()
        assertNull(CarLauncherState.selectedApp.value)
    }

    @Test fun `selected app shows its screen instead of grid`() {
        CarLauncherState.setDiscovered(listOf(maps, music))
        CarLauncherState.selectApp(maps.id)
        rule.setContent { LauncherRoot() }
        // Grid gone: only dock may carry labels; home body is AppScreen (template empty)
        rule.onNodeWithText(ctx.getString(AutoR.string.car_no_apps)).assertDoesNotExist()
    }

    @Test fun `pinned order filters stale ids and falls back to discovered`() {
        CarLauncherState.setDiscovered(listOf(maps, music))
        CarLauncherState.setPinnedOrder(listOf("stale/id", music.id))
        rule.setContent { LauncherRoot() }
        rule.onNodeWithText("Music").assertExists()
        rule.onNodeWithText("Maps").assertExists()
    }

    @Test fun `tapping open app dock icon fires home tap action`() {
        var homeTaps = 0
        CarLauncherState.setDiscovered(listOf(maps, music))
        CarLauncherState.setPinnedOrder(listOf(maps.id))
        CarLauncherState.selectApp(maps.id)
        CarLauncherState.updateActions { it.copy(onAppHomeTap = { homeTaps++ }) }
        rule.setContent { LauncherRoot() }
        // Clickable nodes: home button first, then the dock cell for Maps.
        rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[1].performClick()
        assertEquals(1, homeTaps)
    }
}
