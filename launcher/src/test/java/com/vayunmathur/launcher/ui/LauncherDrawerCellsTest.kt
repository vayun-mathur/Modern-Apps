package com.vayunmathur.launcher.ui

import android.content.ComponentName
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.DrawerActions
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.platform.DrawerUiState
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [DrawerCell] and
 * [PredictionsRow]: grid labels, list layout, work badge, predictions row,
 * and tap-to-launch reaching [DrawerActions].
 */
@RunWith(AndroidJUnit4::class)
class LauncherDrawerCellsTest {

    @get:Rule val rule = createComposeRule()

    private fun app(label: String, work: Boolean = false) = DrawerApp(
        key = ComponentKey(
            ComponentName("com.example.$label", "com.example.$label.Main"),
            if (work) 10L else 0L,
        ),
        label = label,
        isWorkProfile = work,
    )

    private val state = DrawerUiState(apps = listOf(app("Mail"), app("Maps")))

    private fun showCell(
        app: DrawerApp = app("Mail"),
        state: DrawerUiState = this.state,
        listLayout: Boolean = false,
        onLaunch: (ComponentKey) -> Unit = {},
    ) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                DrawerCell(
                    app = app,
                    state = state,
                    actions = object : DrawerActions {
                        override fun launchApp(
                            key: ComponentKey,
                            left: Int,
                            top: Int,
                            right: Int,
                            bottom: Int,
                        ) {
                            onLaunch(key)
                        }
                    },
                    draggable = false,
                    listLayout = listLayout,
                )
            }
        }
    }

    @Test fun gridCellShowsLabel() {
        showCell()
        rule.onNodeWithText("Mail").assertIsDisplayed()
    }

    @Test fun tappingCellLaunches() {
        var launched: ComponentKey? = null
        showCell(onLaunch = { launched = it })
        rule.onNodeWithText("Mail").performClick()
        assertEquals("com.example.Mail", launched?.componentName?.packageName)
    }

    @Test fun listLayoutShowsLabelBesideIcon() {
        showCell(listLayout = true)
        rule.onNodeWithText("Mail").assertIsDisplayed()
    }

    @Test fun workCellShowsLabel() {
        showCell(app = app("WorkMail", work = true))
        rule.onNodeWithText("WorkMail").assertIsDisplayed()
    }

    @Test fun predictionsRowShowsPredictions() {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                PredictionsRow(
                    state = state.copy(predictions = listOf(app("Top"))),
                    actions = DrawerActions.Noop,
                    draggable = false,
                )
            }
        }
        rule.onNodeWithText("Top").assertIsDisplayed()
    }
}
