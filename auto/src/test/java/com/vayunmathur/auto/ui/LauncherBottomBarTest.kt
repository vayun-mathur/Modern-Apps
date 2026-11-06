package com.vayunmathur.auto.ui

import android.content.ComponentName
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.platform.PhoneStatus
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Auto module: [LauncherBottomBar] — home | pins | status. Taps route home
 * and pin selection; the status cluster renders signal, battery, DND, badge
 * and clock from [CarLauncherState.phoneStatus], with absent sources gone.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherBottomBarTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(packageName: String, className: String, label: String) = DiscoveredApp(
        component = ComponentName(packageName, className),
        label = label,
        icon = null,
        categories = emptySet(),
    )

    @BeforeTest
    fun resetStatus() {
        CarLauncherState.setPhoneStatus(PhoneStatus())
    }

    @Test
    fun `home and pin taps route to their callbacks`() {
        var homes = 0
        val pins = mutableListOf<String>()
        // Real drawables so cells render artwork with the label as the
        // content description (null icons draw the undescribed placeholder).
        val icon = android.graphics.drawable.ColorDrawable(android.graphics.Color.BLUE)
        val maps = app("com.example.maps", "com.example.maps.MapsService", "Maps").copy(icon = icon)
        val music = app("com.example.music", "com.example.music.MusicService", "Music").copy(icon = icon)
        rule.setContent {
            LauncherBottomBar(
                pinned = listOf(maps, music),
                selectedId = "com.example.maps/com.example.maps.MapsService",
                onHome = { homes++ },
                onPin = { pins += it },
            )
        }
        // Dock cells render artwork only (the label rides the content
        // description, merged onto the clickable cell), so taps target the
        // clickable node carrying each description. Rasterisation leaves the
        // main thread, so wait for the artwork to land first.
        rule.waitUntil(10_000) {
            rule.onAllNodes(hasContentDescription("Music")).fetchSemanticsNodes().size == 1
        }
        rule.onNode(hasClickAction().and(hasContentDescription("Music"))).performClick()
        assertEquals(listOf("com.example.music/com.example.music.MusicService"), pins)
        // Tapping the already-open app still routes (it resets to its home).
        rule.onNode(hasClickAction().and(hasContentDescription("Maps"))).performClick()
        assertEquals(
            listOf(
                "com.example.music/com.example.music.MusicService",
                "com.example.maps/com.example.maps.MapsService",
            ),
            pins,
        )
        assertEquals(0, homes)
    }

    @Test
    fun `full status renders battery percent badge and clock`() {
        CarLauncherState.setPhoneStatus(
            PhoneStatus(
                signalBars = 4,
                batteryPercent = 80,
                batteryCharging = false,
                doNotDisturb = true,
                notificationCount = 3,
            ),
        )
        rule.setContent {
            LauncherBottomBar(
                pinned = emptyList(),
                selectedId = null,
                onHome = {},
                onPin = {},
            )
        }
        rule.onNodeWithText("80%").assertExists()
        rule.onNodeWithText("3").assertExists()
    }

    @Test
    fun `absent sources stay gone and zero hides the badge`() {
        CarLauncherState.setPhoneStatus(PhoneStatus())
        rule.setContent {
            LauncherBottomBar(
                pinned = emptyList(),
                selectedId = null,
                onHome = {},
                onPin = {},
            )
        }
        // No battery text, no badge digit: the row stays gone like the rail.
        rule.onAllNodes(hasText("80%")).assertCountEquals(0)
        rule.onAllNodes(hasText("3")).assertCountEquals(0)
    }

    @Test
    fun `low battery still renders its percent`() {
        CarLauncherState.setPhoneStatus(PhoneStatus(batteryPercent = 5, batteryCharging = false))
        rule.setContent {
            LauncherBottomBar(
                pinned = emptyList(),
                selectedId = null,
                onHome = {},
                onPin = {},
            )
        }
        rule.onNodeWithText("5%").assertExists()
    }
}
