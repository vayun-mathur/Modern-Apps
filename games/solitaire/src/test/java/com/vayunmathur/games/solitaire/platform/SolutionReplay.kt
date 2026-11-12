package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.domain.DealProofs
import com.vayunmathur.games.solitaire.domain.FreeCellMove
import com.vayunmathur.games.solitaire.domain.FreeCellSolver
import com.vayunmathur.games.solitaire.domain.KlondikeMove
import com.vayunmathur.games.solitaire.domain.KlondikeSolver
import com.vayunmathur.games.solitaire.domain.PyramidMove
import com.vayunmathur.games.solitaire.domain.PyramidSolver
import com.vayunmathur.games.solitaire.domain.SolitaireDeals
import com.vayunmathur.games.solitaire.domain.SolvableDeals
import com.vayunmathur.games.solitaire.domain.SolveResult
import com.vayunmathur.games.solitaire.domain.SpiderMove
import com.vayunmathur.games.solitaire.domain.SpiderSolver
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plays a solver's winning line through the real ViewModel rules, one player
 * action at a time, and checks that every action is accepted and the game
 * ends won. This is the guard against solver/rules drift: if a rule in the
 * `*Ops` files changes, lines stop replaying here and the seed tables must be
 * regenerated (AGENTS.md, "Solvable deals").
 */
internal object SolutionReplay {

    fun vm(): SolitaireViewModel = SolitaireViewModel(ApplicationProvider.getApplicationContext<Application>())

    /** Proves [seed] for [config] along its ladder and replays the line; returns the number of actions. */
    fun proveAndReplay(mode: GameMode, config: GameConfig, seed: Long): Int {
        val (_, result) = requireNotNull(DealProofs.prove(mode, config, seed)) { "$mode $config seed $seed: not proven" }
        return replay(mode, config, seed, result)
    }

    fun replay(mode: GameMode, config: GameConfig, seed: Long, result: SolveResult): Int {
        val vm = vm()
        // The deal exactly as a new game would start it (the line was found on this board).
        vm.uiStateInternal.value = when (mode) {
            GameMode.KLONDIKE -> SolitaireUiState(gameMode = mode, klondike = SolitaireDeals.klondike(seed, config))
            GameMode.SPIDER -> SolitaireUiState(gameMode = mode, spider = SolitaireDeals.spider(seed, config))
            GameMode.FREECELL -> SolitaireUiState(gameMode = mode, freeCell = SolitaireDeals.freeCell(seed))
            GameMode.PYRAMID -> SolitaireUiState(gameMode = mode, pyramid = SolitaireDeals.pyramid(seed, config))
        }
        val label = "$mode ${SolvableDeals.variant(mode, config)} seed=$seed"
        val actions = when (mode) {
            GameMode.KLONDIKE -> KlondikeSolver.decode(result.moves).onEachIndexed { i, m ->
                stepAccepted(vm, "$label step $i $m") { klondikeStep(vm, m) }
            }.size
            GameMode.FREECELL -> FreeCellSolver.decode(result.moves).onEachIndexed { i, m ->
                stepAccepted(vm, "$label step $i $m") { freeCellStep(vm, m) }
            }.size
            GameMode.SPIDER -> SpiderSolver.decode(result.moves).onEachIndexed { i, m ->
                stepAccepted(vm, "$label step $i $m") { spiderStep(vm, m) }
            }.size
            GameMode.PYRAMID -> PyramidSolver.decode(result.moves).onEachIndexed { i, m ->
                stepAccepted(vm, "$label step $i $m") { pyramidStep(vm, m) }
            }.size
        }
        assertTrue(won(vm), "$label: line replayed but the game is not won")
        return actions
    }

    private fun moveCount(vm: SolitaireViewModel): Int = with(vm.uiState.value) {
        klondike?.moveCount ?: spider?.moveCount ?: freeCell?.moveCount ?: pyramid?.moveCount ?: -1
    }

    private fun won(vm: SolitaireViewModel): Boolean = with(vm.uiState.value) {
        klondike?.isWon ?: spider?.isWon ?: freeCell?.isWon ?: pyramid?.isWon ?: false
    }

    /** Every player action counts exactly one move; a rejected action counts none. */
    private inline fun stepAccepted(vm: SolitaireViewModel, label: String, step: () -> Unit) {
        val before = moveCount(vm)
        step()
        assertEquals(before + 1, moveCount(vm), "$label was rejected by the game")
    }

    private fun klondikeStep(vm: SolitaireViewModel, m: KlondikeMove) = when (m) {
        KlondikeMove.Draw -> vm.drawFromStock()
        is KlondikeMove.WasteToFoundation -> vm.klondikeMoveWasteToFoundation(m.foundation)
        is KlondikeMove.WasteToTableau -> vm.klondikeMoveWasteToTableau(m.column)
        is KlondikeMove.TableauToFoundation -> vm.klondikeMoveTableauToFoundation(m.column, m.foundation)
        is KlondikeMove.TableauToTableau -> vm.klondikeMoveTableauToTableau(m.from, m.faceUpIndex, m.to)
    }

    private fun freeCellStep(vm: SolitaireViewModel, m: FreeCellMove) = when (m) {
        is FreeCellMove.TableauToFoundation -> vm.freeCellMoveTableauToFoundation(m.column, m.foundation)
        is FreeCellMove.FreeCellToFoundation -> vm.freeCellMoveFreeCellToFoundation(m.cell, m.foundation)
        is FreeCellMove.TableauToTableau -> vm.freeCellMoveTableauToTableau(m.from, m.cardIndex, m.to)
        is FreeCellMove.FreeCellToTableau -> vm.freeCellMoveFromFreeCell(m.cell, m.column)
        is FreeCellMove.TableauToFreeCell -> vm.freeCellMoveToFreeCell(m.column, m.cell)
    }

    private fun spiderStep(vm: SolitaireViewModel, m: SpiderMove) = when (m) {
        SpiderMove.Deal -> vm.dealSpiderStock()
        is SpiderMove.Move -> vm.spiderMoveCards(m.from, m.faceUpIndex, m.to)
    }

    /** A pair is two taps (select, then match); a King is one tap. */
    private fun pyramidStep(vm: SolitaireViewModel, m: PyramidMove) = when (m) {
        PyramidMove.Deal -> vm.pyramidDealStock()
        is PyramidMove.RemoveKing -> vm.pyramidTapCard(m.id)
        is PyramidMove.RemovePair -> {
            vm.pyramidTapCard(m.first)
            vm.pyramidTapCard(m.second)
        }
    }
}
