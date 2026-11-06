package com.vayunmathur.auto.ui

import android.content.ComponentName
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.domain.PinnedAppRow
import com.vayunmathur.auto.platform.DiscoveredApp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Auto module: stateless [PinnedAppsScreen] and [LauncherHomeGrid]. The pin
 * picker renders every row with up/down/toggle actions; the home grid shows
 * the empty state with no apps and one cell per app otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class PinnedAppsAndGridTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun app(packageName: String, className: String, label: String) = DiscoveredApp(
        component = ComponentName(packageName, className),
        label = label,
        icon = null,
        categories = emptySet(),
    )

    private fun row(packageName: String, className: String, label: String, rank: Int?) =
        PinnedAppRow(app(packageName, className, label), rank)

    @Test
    fun `pin picker renders the body rows and routes toggle and reorder`() {
        val toggled = mutableListOf<String>()
        val up = mutableListOf<String>()
        val down = mutableListOf<String>()
        rule.setContent {
            PinnedAppsScreen(
                onNavigateBack = {},
                rows = listOf(
                    row("com.example.maps", "com.example.maps.MapsService", "Maps", 0),
                    row("com.example.music", "com.example.music.MusicService", "Music", null),
                ),
                onToggle = { toggled += it },
                onMoveUp = { up += it },
                onMoveDown = { down += it },
            )
        }
        rule.onNodeWithText(
            appContext.getString(AutoR.string.pinned_apps_body, 5),
        ).assertExists()
        rule.onNodeWithText("Maps").assertExists()
        rule.onNodeWithText("Music").assertExists()

        // The unchecked Music row's checkbox toggles it; the row label itself
        // carries no click action.
        rule.onNode(isToggleable().and(isOff())).performClick()
        assertEquals(listOf("com.example.music/com.example.music.MusicService"), toggled)
        assertEquals(emptyList(), up)
        assertEquals(emptyList(), down)
    }

    @Test
    fun `home grid shows the empty state with no apps`() {
        var opened = 0
        rule.setContent {
            LauncherHomeGrid(apps = emptyList(), onOpen = { opened++ })
        }
        rule.onNodeWithText(appContext.getString(AutoR.string.car_no_apps)).assertExists()
        assertEquals(0, opened)
    }

    @Test
    fun `home grid renders one cell per app and opens on tap`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            LauncherHomeGrid(
                apps = listOf(
                    app("com.example.maps", "com.example.maps.MapsService", "Maps"),
                    app("com.example.music", "com.example.music.MusicService", "Music"),
                ),
                onOpen = { opened += it },
            )
        }
        rule.onNodeWithText("Maps").assertExists()
        rule.onNodeWithText("Music").assertExists()
        // The empty state must not render alongside real cells.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.car_no_apps)))
            .assertCountEquals(0)
        rule.onNodeWithText("Maps").performClick()
        assertEquals(listOf("com.example.maps/com.example.maps.MapsService"), opened)
    }
}
