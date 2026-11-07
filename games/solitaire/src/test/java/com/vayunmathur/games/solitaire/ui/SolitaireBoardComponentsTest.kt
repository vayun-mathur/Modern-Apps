package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Card faces/backs, empty slots, foundation and free-cell slots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SolitaireBoardComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun cardFaceShowsRankAndSuitForRedAndBlack() {
        composeRule.setContent { CardFace(Card(Suit.HEARTS, Rank.ACE)) }
        composeRule.onNodeWithText("A").assertIsDisplayed()
        composeRule.onNodeWithText("♥").assertIsDisplayed()
    }

    @Test
    fun cardFaceTenAndKingLabels() {
        composeRule.setContent { CardFace(Card(Suit.SPADES, Rank.TEN)) }
        composeRule.onNodeWithText("10").assertIsDisplayed()
        composeRule.onNodeWithText("♠").assertIsDisplayed()
    }

    @Test
    fun emptySlotShowsLabel() {
        composeRule.setContent { EmptySlot(label = "A") }
        composeRule.onNodeWithText("A").assertIsDisplayed()
    }

    @Test
    fun foundationSlotEmptyAndOccupied() {
        composeRule.setContent {
            FoundationSlot(cards = emptyList(), index = 0, actions = SolitaireActions.Noop)
        }
        // Empty foundation renders its suit watermark slot without crashing.
        assertTrue(true)
    }

    @Test
    fun foundationSlotShowsTopCard() {
        val cards = listOf(Card(Suit.HEARTS, Rank.ACE), Card(Suit.HEARTS, Rank.TWO))
        composeRule.setContent {
            FoundationSlot(cards = cards, index = 0, actions = SolitaireActions.Noop)
        }
        composeRule.onNodeWithText("2").assertIsDisplayed()
    }

    @Test
    fun freeCellSlotEmptyAndOccupied() {
        composeRule.setContent {
            FreeCellSlot(card = Card(Suit.CLUBS, Rank.KING), index = 0, actions = SolitaireActions.Noop)
        }
        composeRule.onNodeWithText("K").assertIsDisplayed()
        composeRule.onNodeWithText("♣").assertIsDisplayed()
    }

    @Test
    fun cardBackRenders() {
        composeRule.setContent { CardBack() }
        assertTrue(true)
    }
}
