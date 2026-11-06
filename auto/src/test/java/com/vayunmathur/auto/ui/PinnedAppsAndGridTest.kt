package com.vayunmathur.auto.ui

import android.content.ComponentName
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodes
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

    private fun app(id: String, label: String) = DiscoveredApp(
        component = ComponentName.unflattenFromString(id)!!,
        label = label,
        icon = null,
        categories = emptySet(),
    )

    private fun row(id: String, label: String, rank: Int?) =
        PinnedAppRow(app(id, label), rank)

    @Test
    fun `pin picker renders the body rows and routes toggle and reorder`() {
        val toggled = mutableListOf<String>()
        val up = mutableListOf<String>()
        val down = mutableListOf<String>()
        rule.setContent {
            PinnedAppsScreen(
                onNavigateBack = {},
                rows = listOf(
                    row("pkg/.Maps", "Maps", 0),
                    row("pkg/.Music", "Music", null),
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

        rule.onNodeWithText("Music").performClick()
        assertEquals(listOf("pkg/.Music"), toggled)
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
                apps = listOf(app("pkg/.Maps", "Maps"), app("pkg/.Music", "Music")),
                onOpen = { opened += it },
            )
        }
        rule.onNodeWithText("Maps").assertExists()
        rule.onNodeWithText("Music").assertExists()
        // The empty state must not render alongside real cells.
        rule.onAllNodes(hasText(appContext.getString(AutoR.string.car_no_apps)))
            .assertCountEquals(0)
        rule.onNodeWithText("Maps").performClick()
        assertEquals(listOf("pkg/.Maps"), opened)
    }
}
