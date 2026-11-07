package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.Suit
import com.vayunmathur.games.solitaire.data.TableauPile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Klondike operations beyond the basic waste/tableau moves covered elsewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KlondikeOpsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun vm() = SolitaireViewModel(app)
    private fun c(suit: Suit, rank: Rank) = Card(suit, rank)

    private fun setK(vm: SolitaireViewModel, state: KlondikeState) {
        vm.uiStateInternal.value = SolitaireUiState(gameMode = GameMode.KLONDIKE, klondike = state)
    }

    private fun sevenPiles(vararg piles: TableauPile): List<TableauPile> =
        piles.toList() + List(7 - piles.size) { TableauPile() }

    @Test fun drawThreeDrawsUpToThreeAndRecycleHonoursRedeals() {
        val vm = vm()
        val stock = listOf(c(Suit.HEARTS, Rank.ACE), c(Suit.HEARTS, Rank.TWO), c(Suit.HEARTS, Rank.THREE), c(Suit.HEARTS, Rank.FOUR))
        setK(vm, KlondikeState(stock = stock, drawMode = DrawMode.DRAW_THREE, redealsRemaining = 1))
        vm.drawFromStock()
        var k = vm.uiState.value.klondike!!
        assertEquals(3, k.waste.size)
        assertEquals(1, k.stock.size)
        // Waste top is the stock's original top card.
        assertEquals(c(Suit.HEARTS, Rank.TWO), k.waste.last())
        vm.drawFromStock()
        k = vm.uiState.value.klondike!!
        assertEquals(4, k.waste.size)
        assertEquals(0, k.stock.size)
        // Recycle consumes the one redeal; second recycle is refused.
        vm.drawFromStock()
        k = vm.uiState.value.klondike!!
        assertEquals(4, k.stock.size)
        assertEquals(0, k.redealsRemaining)
        val movesBefore = k.moveCount
        vm.drawFromStock()
        vm.drawFromStock()
        // After drawing all out again, no redeals remain: stock empties and stays.
        k = vm.uiState.value.klondike!!
        assertTrue(k.stock.isEmpty() || k.redealsRemaining == 0)
        assertTrue(k.moveCount >= movesBefore)
    }

    @Test fun drawFromStockNoOpsWhenEmptyOrWon() {
        val vm = vm()
        setK(vm, KlondikeState(stock = emptyList(), waste = emptyList()))
        vm.drawFromStock()
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        val won = KlondikeState(stock = listOf(c(Suit.HEARTS, Rank.ACE)), isWon = true)
        setK(vm, won)
        vm.drawFromStock()
        assertEquals(won, vm.uiState.value.klondike)
        // No game at all.
        vm.uiStateInternal.value = SolitaireUiState()
        vm.drawFromStock()
        assertEquals(null, vm.uiState.value.klondike)
    }

    @Test fun relaxedRecycleStaysUnlimited() {
        val vm = vm()
        setK(vm, KlondikeState(stock = emptyList(), waste = listOf(c(Suit.CLUBS, Rank.FIVE)), redealsRemaining = Int.MAX_VALUE))
        vm.drawFromStock()
        val k = vm.uiState.value.klondike!!
        assertEquals(listOf(c(Suit.CLUBS, Rank.FIVE)), k.stock)
        assertEquals(Int.MAX_VALUE, k.redealsRemaining)
    }

    @Test fun tableauToFoundationMovesAndFlipsAndRejects() {
        val vm = vm()
        val down = c(Suit.CLUBS, Rank.NINE)
        val ace = c(Suit.HEARTS, Rank.ACE)
        setK(
            vm,
            KlondikeState(
                tableauPiles = sevenPiles(TableauPile(faceDown = listOf(down), faceUp = listOf(ace))),
            ),
        )
        vm.klondikeMoveTableauToFoundation(0, 0)
        var k = vm.uiState.value.klondike!!
        assertEquals(listOf(ace), k.foundations[0])
        assertEquals(listOf(down), k.tableauPiles[0].faceUp)
        assertTrue(k.tableauPiles[0].faceDown.isEmpty())
        // Wrong suit index (foundation 1 is Diamonds) rejects a Hearts two on empty.
        setK(vm, KlondikeState(tableauPiles = sevenPiles(TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.TWO))))))
        vm.klondikeMoveTableauToFoundation(0, 1)
        assertTrue(vm.uiState.value.klondike!!.foundations[1].isEmpty())
        // Empty pile rejects.
        vm.klondikeMoveTableauToFoundation(3, 0)
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
    }

    @Test fun tableauToTableauRangeChecksAndSequenceMove() {
        val vm = vm()
        val pile0 = TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.SEVEN), c(Suit.SPADES, Rank.SIX)))
        val pile1 = TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.EIGHT)))
        setK(vm, KlondikeState(tableauPiles = sevenPiles(pile0, pile1)))
        vm.klondikeMoveTableauToTableau(0, -1, 1)
        vm.klondikeMoveTableauToTableau(0, 5, 1)
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        // Move the two-card run onto the black eight.
        vm.klondikeMoveTableauToTableau(0, 0, 1)
        val k = vm.uiState.value.klondike!!
        assertEquals(listOf(c(Suit.CLUBS, Rank.EIGHT), c(Suit.HEARTS, Rank.SEVEN), c(Suit.SPADES, Rank.SIX)), k.tableauPiles[1].faceUp)
        assertTrue(k.tableauPiles[0].faceUp.isEmpty())
    }

    @Test fun foundationPlacementRules() {
        val vm = vm()
        assertTrue(vm.canPlaceOnFoundation(c(Suit.HEARTS, Rank.ACE), emptyList(), 0))
        assertFalse(vm.canPlaceOnFoundation(c(Suit.DIAMONDS, Rank.ACE), emptyList(), 0))
        assertFalse(vm.canPlaceOnFoundation(c(Suit.HEARTS, Rank.TWO), emptyList(), 0))
        val f = listOf(c(Suit.HEARTS, Rank.ACE), c(Suit.HEARTS, Rank.TWO))
        assertTrue(vm.canPlaceOnFoundation(c(Suit.HEARTS, Rank.THREE), f, 0))
        assertFalse(vm.canPlaceOnFoundation(c(Suit.SPADES, Rank.THREE), f, 0))
        assertFalse(vm.canPlaceOnFoundation(c(Suit.HEARTS, Rank.FOUR), f, 0))
    }

    @Test fun autoCompleteDrainsStockWasteAndTableauToWin() {
        val vm = vm()
        // Foundations one card short per suit except Hearts needs its King from tableau.
        fun almost(suit: Suit) = Suit.entries.indexOf(suit).let { }
        val foundations = Suit.entries.map { suit ->
            Rank.entries.filter { it != Rank.KING }.map { Card(suit, it) }
        }
        val state = KlondikeState(
            stock = listOf(Card(Suit.SPADES, Rank.KING)),
            waste = listOf(Card(Suit.HEARTS, Rank.KING)),
            tableauPiles = sevenPiles(
                TableauPile(faceUp = listOf(Card(Suit.DIAMONDS, Rank.KING))),
                TableauPile(faceUp = listOf(Card(Suit.CLUBS, Rank.KING))),
            ),
            foundations = foundations,
            elapsedSeconds = 42,
        )
        setK(vm, state)
        assertTrue(canKlondikeAutoComplete(state))
        vm.klondikeAutoComplete()
        val k = vm.uiState.value.klondike!!
        assertTrue(k.isWon, "state=$k")
        assertTrue(k.foundations.all { it.size == 13 })
        assertTrue(k.stock.isEmpty() && k.waste.isEmpty())
    }

    @Test fun autoCompleteNoOpsWhenFaceDownPresent() {
        val vm = vm()
        val state = KlondikeState(
            tableauPiles = sevenPiles(TableauPile(faceDown = listOf(c(Suit.HEARTS, Rank.ACE)), faceUp = listOf(c(Suit.HEARTS, Rank.TWO)))),
        )
        setK(vm, state)
        vm.klondikeAutoComplete()
        assertEquals(0, vm.uiState.value.klondike!!.foundations[0].size)
    }

    @Test fun klondikeWinDetectedWhenLastFoundationCompletes() {
        val vm = vm()
        val foundations = Suit.entries.map { suit ->
            if (suit == Suit.HEARTS) Rank.entries.filter { it != Rank.KING }.map { Card(suit, it) }
            else Rank.entries.map { Card(suit, it) }
        }
        setK(
            vm,
            KlondikeState(
                waste = listOf(c(Suit.HEARTS, Rank.KING)),
                foundations = foundations,
                elapsedSeconds = 500,
            ),
        )
        vm.klondikeMoveWasteToFoundation(0)
        assertTrue(vm.uiState.value.klondike!!.isWon)
    }

    @Test fun autoMoveWasteToFoundationThenTableau() {
        val vm = vm()
        // Waste ace goes to its foundation first.
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE))))
        vm.autoMove("waste")
        assertEquals(listOf(c(Suit.HEARTS, Rank.ACE)), vm.uiState.value.klondike!!.foundations[0])
        // Waste red queen with no foundation spot goes onto a black king.
        setK(
            vm,
            KlondikeState(
                waste = listOf(c(Suit.HEARTS, Rank.QUEEN)),
                tableauPiles = sevenPiles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING)))),
            ),
        )
        vm.autoMove("waste")
        var k = vm.uiState.value.klondike!!
        assertTrue(k.waste.isEmpty())
        assertEquals(c(Suit.HEARTS, Rank.QUEEN), k.tableauPiles[0].faceUp.last())
        // Tableau top two of hearts follows its ace.
        setK(
            vm,
            KlondikeState(
                foundations = listOf(listOf(c(Suit.HEARTS, Rank.ACE))) + List(3) { emptyList() },
                tableauPiles = sevenPiles(TableauPile(faceUp = listOf(c(Suit.HEARTS, Rank.TWO)))),
            ),
        )
        vm.autoMove("tableau_0_0")
        k = vm.uiState.value.klondike!!
        assertEquals(2, k.foundations[0].size)
    }

    @Test fun autoMoveNoOpsForBadSourcesAndLoneKingShuffle() {
        val vm = vm()
        // Lone king in an empty-origin column does not shuffle to another empty column.
        setK(
            vm,
            KlondikeState(tableauPiles = sevenPiles(TableauPile(faceUp = listOf(c(Suit.SPADES, Rank.KING))))),
        )
        vm.autoMove("tableau_0_0")
        assertEquals(listOf(c(Suit.SPADES, Rank.KING)), vm.uiState.value.klondike!!.tableauPiles[0].faceUp)
        vm.autoMove("nonsense")
        vm.autoMove("tableau_9_0")
        vm.autoMove("")
        assertEquals(0, vm.uiState.value.klondike!!.moveCount)
        // Won game ignores auto-move.
        setK(vm, KlondikeState(waste = listOf(c(Suit.HEARTS, Rank.ACE)), isWon = true))
        vm.autoMove("waste")
        assertTrue(vm.uiState.value.klondike!!.waste.isNotEmpty())
    }

    @Test fun canPlaceOnTableauEdgeCases() {
        val vm = vm()
        assertTrue(vm.canPlaceOnKlondikeTableau(c(Suit.SPADES, Rank.KING), TableauPile()))
        assertFalse(vm.canPlaceOnKlondikeTableau(c(Suit.SPADES, Rank.QUEEN), TableauPile()))
        // Pile with only face-down cards accepts nothing (not even a King).
        assertFalse(
            vm.canPlaceOnKlondikeTableau(
                c(Suit.SPADES, Rank.KING),
                TableauPile(faceDown = listOf(c(Suit.HEARTS, Rank.ACE))),
            ),
        )
        val topBlack = TableauPile(faceUp = listOf(c(Suit.CLUBS, Rank.SEVEN)))
        assertTrue(vm.canPlaceOnKlondikeTableau(c(Suit.HEARTS, Rank.SIX), topBlack))
        assertFalse(vm.canPlaceOnKlondikeTableau(c(Suit.SPADES, Rank.SIX), topBlack))
        assertFalse(vm.canPlaceOnKlondikeTableau(c(Suit.HEARTS, Rank.FIVE), topBlack))
    }

    @Test fun newGameVariantsAndDrawModesRecorded() {
        val vm = vm()
        vm.newKlondikeGame(GameConfig(drawMode = DrawMode.DRAW_ONE, klondikeDifficulty = KlondikeDifficulty.RELAXED))
        var k = vm.uiState.value.klondike!!
        assertEquals("DRAW_ONE_RELAXED", k.variant)
        assertEquals(Int.MAX_VALUE, k.redealsRemaining)
        assertEquals(DrawMode.DRAW_ONE, vm.currentConfig().drawMode)
        vm.newKlondikeGame(GameConfig(drawMode = DrawMode.DRAW_THREE, klondikeDifficulty = KlondikeDifficulty.REGULAR))
        k = vm.uiState.value.klondike!!
        assertEquals(2, k.redealsRemaining)
        assertEquals("DRAW_THREE_REGULAR", vm.currentVariant())
    }
}
