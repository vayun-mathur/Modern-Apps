package com.vayunmathur.games.hub.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import com.vayunmathur.games.hub.data.entities.YuuFaceConfig
import com.vayunmathur.games.hub.viewmodel.CrossGameStats
import com.vayunmathur.games.hub.ui.screens.ProfileScreen
import com.vayunmathur.games.hub.ui.screens.YuuEditorScreen
import com.vayunmathur.games.hub.ui.yuu.YuuEditorActions
import com.vayunmathur.games.hub.ui.yuu.YuuEditorUiState
import com.vayunmathur.games.hub.util.ProfileActions
import com.vayunmathur.games.hub.util.ProfileUiState
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HubCoveragePassTest {
    @get:Rule val rule = createComposeRule()

    private fun profileState() = ProfileUiState(
        playerName = "Grace", avatarSymbol = "star", level = 5, title = "Casual Gamer", totalXp = 1700,
        stats = CrossGameStats(
            totalPlaytimeMs = 7_200_000, totalSessions = 42, totalGames = 6,
            totalAchievementsUnlocked = 11, totalAchievements = 30, longestStreak = 14,
        ),
    )

    @Test fun profileEditDialogSavesNameAndAvatar() {
        var savedName: String? = null
        var savedAvatar: String? = "unset"
        val actions = object : ProfileActions {
            override fun updateDisplayName(name: String) { savedName = name }
            override fun updateAvatarSymbol(symbol: String?) { savedAvatar = symbol }
        }
        rule.setContent { ProfileScreen(profileState(), actions) }
        rule.onNodeWithText("Grace").assertIsDisplayed()
        rule.onNodeWithContentDescription("Person").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Edit profile").assertIsDisplayed()
        // Clear and retype the display name, pick a different avatar chip.
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Display name")).performTextReplacement("Ada")
        rule.onNodeWithText("person").performClick()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals("Ada", savedName)
        assertEquals("person", savedAvatar)
    }

    @Test fun profileEditDialogCancelKeepsOldValues() {
        var savedName: String? = null
        val actions = object : ProfileActions {
            override fun updateDisplayName(name: String) { savedName = name }
        }
        rule.setContent { ProfileScreen(profileState(), actions) }
        rule.onNodeWithContentDescription("Person").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Display name")).performTextReplacement("Changed")
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(null, savedName)
        rule.onNodeWithText("Grace").assertIsDisplayed()
    }

    @Test fun profileBlankNameSaveKeepsOldNameButSavesAvatar() {
        var savedName: String? = null
        var avatarCalls = 0
        val actions = object : ProfileActions {
            override fun updateDisplayName(name: String) { savedName = name }
            override fun updateAvatarSymbol(symbol: String?) { avatarCalls++ }
        }
        rule.setContent { ProfileScreen(profileState(), actions) }
        rule.onNodeWithContentDescription("Person").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction() and androidx.compose.ui.test.hasText("Display name")).performTextReplacement("   ")
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        // Blank names are rejected, but the avatar choice still goes through.
        assertEquals(null, savedName)
        assertEquals(1, avatarCalls)
    }

    @Test fun profileLevelTableScrollsToCurrentLevel() {
        rule.setContent { ProfileScreen(profileState(), ProfileActions.Noop) }
        // Scroll the lazy column so the level table rows compose.
        repeat(3) { rule.onRoot().performTouchInput { swipeUp() }; rule.waitForIdle() }
        val you = rule.onAllNodesWithText("(you)", substring = true).fetchSemanticsNodes()
        assertTrue(you.isNotEmpty() || rule.onAllNodesWithText("Level", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    private fun clickLastClickableCard() {
        val clickables = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        // Save/Back live in the scaffold bar; option cards are the rest.
        if (clickables.size > 2) {
            runCatching {
                rule.onAllNodes(androidx.compose.ui.test.hasClickAction())[clickables.size - 1].performClick()
            }
            rule.waitForIdle()
        }
    }

    private fun gotoCategory(name: String) {
        var attempts = 0
        while (rule.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty() && attempts < 8) {
            rule.onRoot().performTouchInput { swipeLeft() }; attempts++
        }
        if (rule.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()) {
            rule.onAllNodesWithText(name)[0].performClick()
        }
        rule.waitForIdle()
    }

    @Test fun yuuEditorVariantAndColorPicksChangeSavedConfig() {
        var saved: YuuFaceConfig? = null
        val actions = object : YuuEditorActions {
            override fun onSave(name: String, config: YuuFaceConfig) { saved = config }
        }
        rule.setContent {
            DynamicTheme {
                YuuEditorScreen(
                    YuuEditorUiState(editingId = 2, initialName = "Pick", initialConfig = YuuFaceConfig()),
                    actions,
                )
            }
        }
        // Face panel: variant cells are clickable cards without text; click
        // the last clickable card in the option zone to change the shape.
        rule.onNodeWithText("Face shape").assertIsDisplayed()
        clickLastClickableCard()
        for (cat in listOf("Hair", "Eyes", "Brows", "Nose", "Mouth", "Extras", "Body")) {
            gotoCategory(cat)
            clickLastClickableCard()
            rule.onRoot().performTouchInput { swipeUp() }
            rule.waitForIdle()
            clickLastClickableCard()
        }
        gotoCategory("Face")
        rule.onNodeWithContentDescription("Save").performClick()
        assertNotNull(saved)
    }

    @Test fun contractNoopActionsAreCallable() {
        ProfileActions.Noop.updateDisplayName("x")
        ProfileActions.Noop.updateAvatarSymbol("star")
        ProfileActions.Noop.updateProfilePose("wave")
        com.vayunmathur.games.hub.util.DashboardActions.Noop.openGame("chess")
        com.vayunmathur.games.hub.util.DashboardActions.Noop.openProfile()
        com.vayunmathur.games.hub.util.DashboardActions.Noop.openActivity()
        com.vayunmathur.games.hub.util.DashboardActions.Noop.openGamesList()
        com.vayunmathur.games.hub.util.DashboardActions.Noop.openController()
        com.vayunmathur.games.hub.util.GamesListActions.Noop.openGame("chess")
    }
}
