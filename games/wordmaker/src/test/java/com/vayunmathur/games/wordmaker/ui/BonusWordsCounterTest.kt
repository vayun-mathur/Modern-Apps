package com.vayunmathur.games.wordmaker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.games.wordmaker.ui.dialogs.BonusWordsDialog
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bonus word count in the dialog title and on the bonus button (#915). */
@RunWith(RobolectricTestRunner::class)
class BonusWordsCounterTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun badgeTextCapsAtNinetyNine() {
        assertEquals("1", bonusBadgeText(1))
        assertEquals("12", bonusBadgeText(12))
        assertEquals("99", bonusBadgeText(99))
        assertEquals("99+", bonusBadgeText(100))
    }

    @Test
    fun dialogTitleCountsOneWord() {
        rule.setContent {
            DynamicTheme { BonusWordsDialog(bonusWords = setOf("OWL"), getDefinition = { emptyList() }, onDismiss = {}) }
        }
        rule.onNodeWithText("Bonus Word (1)").assertIsDisplayed()
    }

    @Test
    fun dialogTitleCountsSeveralWords() {
        rule.setContent {
            DynamicTheme {
                BonusWordsDialog(bonusWords = setOf("OWL", "COW", "LOW"), getDefinition = { emptyList() }, onDismiss = {})
            }
        }
        rule.onNodeWithText("Bonus Words (3)").assertIsDisplayed()
    }

    @Test
    fun bonusButtonShowsCountBadgeAndOpens() {
        var opened = false
        rule.setContent {
            DynamicTheme {
                Box {
                    WordGameCornerButtons(
                        isWon = false,
                        isCompetitive = true,
                        bonusWords = setOf("OWL", "COW"),
                        bonusShake = { 0f },
                        onOpenBonusWords = { opened = true },
                        onBonusButtonPositioned = {},
                        hintCooldownEnd = 0L,
                        onOpenHint = {},
                    )
                }
            }
        }
        rule.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithContentDescription("Bonus Words").performClick()
        assertTrue(opened)
    }

    @Test
    fun bonusButtonWithoutWordsHasNoBadge() {
        rule.setContent {
            DynamicTheme {
                Box {
                    WordGameCornerButtons(
                        isWon = false,
                        isCompetitive = true,
                        bonusWords = emptySet(),
                        bonusShake = { 0f },
                        onOpenBonusWords = {},
                        onBonusButtonPositioned = {},
                        hintCooldownEnd = 0L,
                        onOpenHint = {},
                    )
                }
            }
        }
        rule.onNodeWithContentDescription("Bonus Words").assertIsNotEnabled()
        assertTrue(rule.onAllNodesWithText("0", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }
}
