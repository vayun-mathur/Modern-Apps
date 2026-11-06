package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.platform.DrawerUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [DrawerContent]: the
 * app list renders, the search field reports queries through
 * [DrawerActions], work tabs split profiles, and empty states read correctly.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDrawerUiTest {

    @get:Rule val rule = createComposeRule()

    private fun app(pkg: String, label: String, work: Boolean = false) = DrawerApp(
        key = ComponentKey(ComponentName(pkg, "$pkg.Main"), if (work) 10L else 0L),
        label = label,
        isWorkProfile = work,
    )

    private val apps = listOf(
        app("com.example.mail", "Mail"),
        app("com.example.maps", "Maps"),
        app("com.example.work", "Work Mail", work = true),
    )

    private fun show(
        state: DrawerUiState = DrawerUiState(apps = apps),
        onQuery: (String) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
            DrawerContent(
                state = state,
                actions = object : DrawerActions {
                    override fun setQuery(query: String) = onQuery(query)
                },
            )
            }
        }
    }

    @Test fun personalAppsRenderOnPersonalTab() {
        show()
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
        rule.onNodeWithText("Personal").assertIsDisplayed()
        rule.onNodeWithText("Work").assertIsDisplayed()
    }

    @Test fun queryTextReachesActions() {
        val queries = mutableListOf<String>()
        show(onQuery = { queries += it })
        rule.onNode(hasSetTextAction()).performTextInput("ma")
        assertEquals(listOf("ma"), queries)
    }

    @Test fun workTabShowsOnlyWorkApps() {
        show()
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Work Mail").assertIsDisplayed()
    }

    @Test fun emptyListReadsNoAppsFound() {
        show(state = DrawerUiState(apps = emptyList()))
        rule.onNodeWithText("No apps found").assertIsDisplayed()
    }

    @Test fun loadingReadsLoadingApps() {
        show(state = DrawerUiState(apps = emptyList(), loading = true))
        rule.onNodeWithText("Loading apps").assertIsDisplayed()
    }

    @Test fun listLayoutRendersRows() {
        show(state = DrawerUiState(apps = apps, listLayout = true))
        rule.onNodeWithText("Mail").assertIsDisplayed()
        rule.onNodeWithText("Maps").assertIsDisplayed()
    }

    @Test fun predictionsRenderAboveList() {
        show(
            state = DrawerUiState(
                apps = apps,
                predictions = listOf(
                    DrawerApp(
                        key = ComponentKey(
                            ComponentName("com.example.top", "com.example.top.Main"),
                            0L,
                        ),
                        label = "TopApp",
                    ),
                ),
            ),
        )
        rule.onNodeWithText("TopApp").assertIsDisplayed()
    }

    @Test fun pauseWorkSwitchShowsWhenSet() {
        show(
            state = DrawerUiState(
                apps = apps + DrawerApp(
                    key = ComponentKey(ComponentName("com.work.a", "com.work.a.Main"), 10L),
                    label = "WorkApp",
                    isWorkProfile = true,
                ),
                workPaused = true,
            ),
        )
        rule.onNodeWithText("Work").performClick()
        rule.onNodeWithText("Pause work apps").assertIsDisplayed()
    }

    @Test fun filteredEmptyReadsNoMatchWithQuery() {
        show(state = DrawerUiState(query = "zzz", apps = emptyList()))
        rule.onNodeWithText("No apps match \"zzz\"").assertIsDisplayed()
    }
}
