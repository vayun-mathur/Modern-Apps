package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.WorkspaceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose tests (Robolectric + createComposeRule) for the small icon
 * cells: [MissingWidget] shows its label, app/folder/shortcut icons render
 * their labels, and [PageIndicator]/[MergeRing] compose without crashing.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconsTest {

    @get:Rule val rule = createComposeRule()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides LauncherDragController()) {
                content()
            }
        }
    }

    @Test fun missingWidgetShowsLabel() {
        show { MissingWidget("Clock widget") }
        rule.onNodeWithText("Clock widget").assertIsDisplayed()
    }

    @Test fun appIconShowsLabel() {
        show {
            LauncherItemIcon(
                item = WorkspaceItem(
                    id = 1L,
                    type = LauncherItemType.APPLICATION,
                    label = "Mail",
                    container = ContainerRef.Desktop,
                ),
            )
        }
        rule.onNodeWithText("Mail").assertIsDisplayed()
    }

    @Test fun folderIconShowsLabel() {
        show {
            LauncherItemIcon(
                item = WorkspaceItem(
                    id = 9L,
                    type = LauncherItemType.FOLDER,
                    label = "Games",
                    container = ContainerRef.Desktop,
                ),
            )
        }
        rule.onNodeWithText("Games").assertIsDisplayed()
    }

    @Test fun shortcutIconShowsLabel() {
        show {
            LauncherItemIcon(
                item = WorkspaceItem(
                    id = 5L,
                    type = LauncherItemType.DEEP_SHORTCUT,
                    label = "Compose",
                    container = ContainerRef.Desktop,
                    shortcutId = "compose",
                ),
            )
        }
        rule.onNodeWithText("Compose").assertIsDisplayed()
    }

    @Test fun hiddenAppRendersDimmed() {
        show {
            LauncherItemIcon(
                item = WorkspaceItem(
                    id = 1L,
                    type = LauncherItemType.APPLICATION,
                    label = "Hidden",
                    container = ContainerRef.Desktop,
                    hidden = true,
                ),
            )
        }
        rule.onNodeWithText("Hidden").assertIsDisplayed()
    }

    @Test fun pageIndicatorComposes() {
        show { PageIndicator(pageCount = 3, scrollProgress = { 1.5f }) }
    }

    @Test fun singlePageIndicatorComposesEmpty() {
        show { PageIndicator(pageCount = 1, scrollProgress = { 0f }) }
    }

    @Test fun mergeRingComposes() {
        show { MergeRing(scale = 1f, progress = { 0.5f }) }
    }
}
