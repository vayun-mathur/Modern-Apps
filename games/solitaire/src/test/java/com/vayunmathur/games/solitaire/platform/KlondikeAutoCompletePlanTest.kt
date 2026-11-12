package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure "can auto-complete finish" plan: foundation moves, draws, recycles, pass limits. */
class KlondikeAutoCompletePlanTest {

    private fun hearts(rank: Rank) = Card(Suit.HEARTS, rank)

    /** Spades, diamonds and clubs complete; hearts up to (not including) [nextHeart]. */
    private fun foundationsUpTo(nextHeart: Rank): List<List<Card>> = Suit.entries.map { suit ->
        if (suit == Suit.HEARTS) Rank.entries.filter { it.value < nextHeart.value }.map { hearts(it) }
        else Rank.entries.map { Card(suit, it) }
    }

    private fun piles(vararg piles: TableauPile) = piles.toList() + List(7 - piles.size) { TableauPile() }

    private fun KlondikeState.finish(): KlondikeState? = planKlondikeAutoComplete(this)?.lastOrNull()?.after

    private fun KlondikeState.isComplete() = foundations.all { it.size == 13 }

    @Test fun finishesFromTableauWasteAndStock() {
        val state = KlondikeState(
            stock = listOf(hearts(Rank.KING)),
            waste = listOf(hearts(Rank.JACK)),
            tableauPiles = piles(TableauPile(faceUp = listOf(hearts(Rank.QUEEN), hearts(Rank.TEN)))),
            foundations = foundationsUpTo(Rank.TEN),
        )
        val plan = assertNotNull(planKlondikeAutoComplete(state))
        assertTrue(plan.last().after.isComplete())
        assertTrue(canKlondikeAutoComplete(state))
        // The tableau ten, the waste jack, the tableau queen, then the stock king.
        val flown = plan.filterIsInstance<AutoCompleteStep.ToFoundation>()
        assertEquals(listOf(Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING), flown.map { it.card.rank })
        assertEquals(listOf("tableau_0_1", "waste", "tableau_0_0", "waste"), flown.map { it.sourceId })
    }

    @Test fun recyclesTheStockWhenAPassIsLeft() {
        // The waste top (K) is buried on top of the Q it needs: only a recycle reaches the Q.
        val state = KlondikeState(
            waste = listOf(hearts(Rank.QUEEN), hearts(Rank.KING)),
            foundations = foundationsUpTo(Rank.QUEEN),
            redealsRemaining = 1,
        )
        val plan = assertNotNull(planKlondikeAutoComplete(state))
        assertIs<AutoCompleteStep.Recycle>(plan.first())
        val end = plan.last().after
        assertTrue(end.isComplete())
        assertEquals(0, end.redealsRemaining)
        // Out of passes, the same board cannot finish, so it is not offered.
        assertNull(planKlondikeAutoComplete(state.copy(redealsRemaining = 0)))
        assertFalse(canKlondikeAutoComplete(state.copy(redealsRemaining = 0)))
    }

    @Test fun unlimitedPassesStayUnlimited() {
        val state = KlondikeState(
            waste = listOf(hearts(Rank.QUEEN), hearts(Rank.KING)),
            foundations = foundationsUpTo(Rank.QUEEN),
            redealsRemaining = Int.MAX_VALUE,
        )
        assertEquals(Int.MAX_VALUE, state.finish()!!.redealsRemaining)
    }

    @Test fun drawThreeRespectsItsTripletsAndPassLimit() {
        // Stock top is the ten: drawing one at a time reaches 10, J, Q, K in order.
        val stock = listOf(hearts(Rank.KING), hearts(Rank.QUEEN), hearts(Rank.JACK), hearts(Rank.TEN))
        val base = KlondikeState(stock = stock, foundations = foundationsUpTo(Rank.TEN), redealsRemaining = 0)
        assertTrue(base.copy(drawMode = DrawMode.DRAW_ONE).finish()!!.isComplete())
        // Drawing three puts the queen on top of the ten: no single pass can finish...
        val drawThree = base.copy(drawMode = DrawMode.DRAW_THREE)
        assertNull(planKlondikeAutoComplete(drawThree))
        val firstDraw = assertIs<AutoCompleteStep.Draw>(nextKlondikeAutoCompleteStep(drawThree))
        assertEquals(hearts(Rank.QUEEN), firstDraw.after.waste.last())
        assertEquals(listOf(hearts(Rank.KING)), firstDraw.after.stock)
        // ...and every pass deals the same triplets, so even unlimited passes would cycle.
        assertNull(planKlondikeAutoComplete(drawThree.copy(redealsRemaining = Int.MAX_VALUE)))
        assertNull(planKlondikeAutoComplete(drawThree.copy(redealsRemaining = 5)))
    }

    @Test fun drawThreeFinishesWhenTheTripletsLineUp() {
        // Draw-three order: the third card from the top surfaces first.
        val stock = listOf(hearts(Rank.KING), hearts(Rank.TEN), hearts(Rank.JACK), hearts(Rank.QUEEN))
        val state = KlondikeState(
            stock = stock,
            drawMode = DrawMode.DRAW_THREE,
            foundations = foundationsUpTo(Rank.TEN),
            redealsRemaining = 1,
        )
        val plan = assertNotNull(planKlondikeAutoComplete(state))
        assertTrue(plan.last().after.isComplete())
    }

    @Test fun neverOfferedWithFaceDownCardsOrWhenWon() {
        val faceDown = KlondikeState(
            tableauPiles = piles(TableauPile(faceDown = listOf(hearts(Rank.KING)), faceUp = listOf(hearts(Rank.QUEEN)))),
            foundations = foundationsUpTo(Rank.QUEEN),
        )
        assertNull(planKlondikeAutoComplete(faceDown))
        val won = KlondikeState(foundations = foundationsUpTo(Rank.KING).let { f ->
            f.mapIndexed { i, pile -> if (i == 0) pile + hearts(Rank.KING) else pile }
        }, isWon = true)
        assertNull(planKlondikeAutoComplete(won))
        assertFalse(canKlondikeAutoComplete(won))
    }

    @Test fun stuckBoardWithEmptyStockIsNotOffered() {
        // Hearts need the ten, but only J..K are left, so nothing can ever move.
        val state = KlondikeState(
            waste = listOf(hearts(Rank.JACK)),
            tableauPiles = piles(TableauPile(faceUp = listOf(hearts(Rank.KING), hearts(Rank.QUEEN)))),
            foundations = foundationsUpTo(Rank.TEN),
            redealsRemaining = Int.MAX_VALUE,
        )
        assertNull(planKlondikeAutoComplete(state))
    }

    @Test fun nextStepPrefersWasteThenTableauThenDrawThenRecycle() {
        val f = foundationsUpTo(Rank.TEN)
        val waste = KlondikeState(
            waste = listOf(hearts(Rank.TEN)),
            tableauPiles = piles(TableauPile(faceUp = listOf(hearts(Rank.TEN)))),
            foundations = f,
        )
        assertEquals("waste", assertIs<AutoCompleteStep.ToFoundation>(nextKlondikeAutoCompleteStep(waste)).sourceId)
        val draw = KlondikeState(stock = listOf(hearts(Rank.JACK)), waste = listOf(hearts(Rank.KING)), foundations = f)
        assertIs<AutoCompleteStep.Draw>(nextKlondikeAutoCompleteStep(draw))
        val recycle = KlondikeState(waste = listOf(hearts(Rank.KING)), foundations = f, redealsRemaining = 1)
        assertIs<AutoCompleteStep.Recycle>(nextKlondikeAutoCompleteStep(recycle))
        assertNull(nextKlondikeAutoCompleteStep(recycle.copy(redealsRemaining = 0)))
    }
}
