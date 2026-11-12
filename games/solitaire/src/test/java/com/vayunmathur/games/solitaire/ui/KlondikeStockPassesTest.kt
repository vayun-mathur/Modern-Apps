package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.ui.dialogs.GameConfigDialog
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Passes left on Klondike's empty stock slot (#911). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KlondikeStockPassesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val waste = listOf(Card(Suit.HEARTS, Rank.FIVE), Card(Suit.SPADES, Rank.NINE))

    @Test
    fun passesLeftFollowsRedealsAndWaste() {
        assertEquals(2, klondikePassesLeft(KlondikeState(waste = waste, redealsRemaining = 2)))
        assertEquals(1, klondikePassesLeft(KlondikeState(waste = waste, redealsRemaining = 1)))
        assertEquals(UNLIMITED_PASSES, klondikePassesLeft(KlondikeState(waste = waste, redealsRemaining = Int.MAX_VALUE)))
        // No redeals left, or nothing to recycle: the slot cannot be used.
        assertNull(klondikePassesLeft(KlondikeState(waste = waste, redealsRemaining = 0)))
        assertNull(klondikePassesLeft(KlondikeState(waste = emptyList(), redealsRemaining = 2)))
    }

    @Test
    fun stockLabelShowsRecycleGlyphAndCount() {
        assertEquals("", klondikeStockLabel(null))
        assertEquals("↻", klondikeStockLabel(UNLIMITED_PASSES))
        assertEquals("↻ 2", klondikeStockLabel(2))
        assertEquals("↻ 1", klondikeStockLabel(1))
    }

    @Test
    fun emptyStockShowsPassesLeftAndRecyclesOnTap() {
        val actions = RecordingActions()
        composeRule.setContent {
            KlondikeBoard(KlondikeState(stock = emptyList(), waste = waste, redealsRemaining = 2), actions)
        }
        composeRule.onNodeWithText("↻ 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2 passes left").performClick()
        assertTrue(actions.calls.contains("drawFromStock"))
    }

    @Test
    fun emptyStockInUnlimitedGameShowsBareGlyph() {
        composeRule.setContent {
            KlondikeBoard(
                KlondikeState(stock = emptyList(), waste = waste, redealsRemaining = Int.MAX_VALUE),
                RecordingActions(),
            )
        }
        composeRule.onNodeWithText("↻").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Unlimited passes").assertIsDisplayed()
    }

    @Test
    fun newGameDialogOffersEveryPassCount() {
        var started: GameConfig? = null
        composeRule.setContent {
            GameConfigDialog(mode = GameMode.KLONDIKE, onStart = { started = it }, onDismiss = {})
        }
        composeRule.onNodeWithText("Passes through the stock").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Unlimited passes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3 passes").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1 pass").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2 passes").performClick()
        composeRule.onNodeWithText("New Game").performClick()
        assertEquals(KlondikeDifficulty.TWO_PASSES, started?.klondikeDifficulty)
    }
}
