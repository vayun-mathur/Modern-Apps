package com.vayunmathur.launcher.tv.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.tv.platform.TvAppEntry
import com.vayunmathur.launcher.tv.platform.TvHomeState
import com.vayunmathur.launcher.tv.platform.TvTile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule) for [TvEditMenu]: the
 * edit dialog names the tile and offers Move/Replace/Remove plus
 * Uninstall (user apps) or App info (system apps); the add menu lists
 * candidates.
 */
@RunWith(AndroidJUnit4::class)
class LauncherTvEditMenuTest {

    @get:Rule val rule = createComposeRule()

    private fun tile(pkg: String, label: String, user: Boolean = true) = TvTile(
        packageName = pkg,
        label = label,
        banner = null,
        appIcon = null,
        userInstalled = user,
    )

    private fun show(
        state: TvHomeState,
        onStartMove: (String) -> Unit = {},
        onRemove: () -> Unit = {},
        onUninstallOrInfo: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        rule.setContent {
            TvEditMenu(
                state = state,
                onDismiss = onDismiss,
                onStartMove = onStartMove,
                onReplace = {},
                onRemove = onRemove,
                onUninstallOrInfo = onUninstallOrInfo,
                onPickAddCandidate = {},
                onCloseAddMenu = {},
            )
        }
    }

    @Test fun userAppMenuOffersUninstall() {
        show(TvHomeState(rowApps = listOf(tile("com.example.a", "Alpha")), editingKey = "com.example.a"))
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Move").assertIsDisplayed()
        rule.onNodeWithText("Replace").assertIsDisplayed()
        rule.onNodeWithText("Remove from home").assertIsDisplayed()
        rule.onNodeWithText("Uninstall").assertIsDisplayed()
    }

    @Test fun systemAppMenuOffersAppInfo() {
        show(
            TvHomeState(
                rowApps = listOf(tile("com.android.sys", "System", user = false)),
                editingKey = "com.android.sys",
            ),
        )
        rule.onNodeWithText("App info").assertIsDisplayed()
    }

    @Test fun moveRowStartsMove() {
        var moved: String? = null
        show(
            TvHomeState(rowApps = listOf(tile("com.example.a", "Alpha")), editingKey = "com.example.a"),
            onStartMove = { moved = it },
        )
        rule.onNodeWithText("Move").performClick()
        assertEquals("com.example.a", moved)
    }

    @Test fun removeRowRemoves() {
        var removed = false
        show(
            TvHomeState(rowApps = listOf(tile("com.example.a", "Alpha")), editingKey = "com.example.a"),
            onRemove = { removed = true },
        )
        rule.onNodeWithText("Remove from home").performClick()
        assertEquals(true, removed)
    }

    @Test fun addMenuListsCandidates() {
        var picked: String? = null
        rule.setContent {
            TvEditMenu(
                state = TvHomeState(
                    showAddMenu = true,
                    addCandidates = listOf(
                        TvAppEntry("com.example.a", "Alpha", true),
                        TvAppEntry("com.example.b", "Beta", true),
                    ),
                ),
                onDismiss = {},
                onStartMove = {},
                onReplace = {},
                onRemove = {},
                onUninstallOrInfo = {},
                onPickAddCandidate = { picked = it },
                onCloseAddMenu = {},
            )
        }
        rule.onNodeWithText("Add an app").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", picked)
    }

    @Test fun addMenuEmptyReadsEveryAppHere() {
        rule.setContent {
            TvEditMenu(
                state = TvHomeState(showAddMenu = true),
                onDismiss = {},
                onStartMove = {},
                onReplace = {},
                onRemove = {},
                onUninstallOrInfo = {},
                onPickAddCandidate = {},
                onCloseAddMenu = {},
            )
        }
        rule.onNodeWithText("Every app is already here").assertIsDisplayed()
    }

    @Test fun replaceMenuNamesCurrent() {
        rule.setContent {
            TvEditMenu(
                state = TvHomeState(
                    rowApps = listOf(tile("com.example.a", "Alpha")),
                    showAddMenu = true,
                    addReplacesIndex = 0,
                    addCandidates = listOf(TvAppEntry("com.example.b", "Beta", true)),
                ),
                onDismiss = {},
                onStartMove = {},
                onReplace = {},
                onRemove = {},
                onUninstallOrInfo = {},
                onPickAddCandidate = {},
                onCloseAddMenu = {},
            )
        }
        rule.onNodeWithText("Replace Alpha").assertIsDisplayed()
    }

    @Test fun noEditingKeyRendersNothing() {
        show(TvHomeState(rowApps = listOf(tile("com.example.a", "Alpha"))))
        // The tile grid is not this composable's business; only the dialog is.
        assertEquals(
            0,
            rule.onAllNodesWithText("Move").fetchSemanticsNodes().size,
        )
    }
}
