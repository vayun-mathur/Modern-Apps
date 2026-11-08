package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.PYRAMID_ROWS
import com.vayunmathur.games.solitaire.data.PAIR_TARGET
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.Rank
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.createShuffledDeck
import kotlinx.coroutines.flow.update

// ---- Pyramid ----
// Moved from SolitaireViewModel.kt (FileLength split); behavior identical.

fun SolitaireViewModel.newPyramidGame(config: GameConfig) = newPyramidGameImpl(config)

internal fun SolitaireViewModel.newPyramidGameImpl(config: GameConfig) {
    val deck = createShuffledDeck()
    var index = 0
    val rows = mutableListOf<List<Card?>>()
    for (r in 0 until PYRAMID_ROWS) {
        val row = mutableListOf<Card?>()
        for (c in 0..r) {
            row.add(deck[index]); index++
        }
        rows.add(row)
    }
    val stock = deck.subList(index, deck.size).toList()
    val variant = if (config.relaxed) "RELAXED" else "ORIGINAL"
    uiStateInternal.value = SolitaireUiState(
        gameMode = GameMode.PYRAMID,
        pyramid = PyramidState(
            pyramid = rows,
            stock = stock,
            waste = emptyList(),
            relaxed = config.relaxed,
            variant = variant
        ),
        history = emptyList()
    )
    statsRepository.recordGamePlayed(GameMode.PYRAMID, variant)
}

/** A pyramid card is exposed when both cards resting on it have been removed. */
internal fun SolitaireViewModel.isPyramidCardExposed(state: PyramidState, row: Int, col: Int): Boolean {
    if (state.pyramid[row][col] == null) return false
    if (row == state.pyramid.lastIndex) return true
    val below = state.pyramid[row + 1]
    return below[col] == null && below[col + 1] == null
}

/** Whether the card identified by [id] is exposed (directly selectable). */
internal fun SolitaireViewModel.isPyramidPlayable(state: PyramidState, id: String): Boolean = when {
    id == "waste" -> state.waste.isNotEmpty()
    id.startsWith("pyr_") -> {
        val (r, c) = parsePyramidId(id)
        isPyramidCardExposed(state, r, c)
    }
    else -> false
}

/**
 * Whether the card [coverId] is the *only* remaining card covering
 * [coveredId] (i.e. removing [coverId] would expose it). This enables the
 * standard Pyramid variation: an exposed card may be taken together with a
 * card it partially covers, when it is that card's last cover.
 */
internal fun SolitaireViewModel.isPyramidSoleCover(state: PyramidState, coverId: String, coveredId: String): Boolean {
    if (!coverId.startsWith("pyr_") || !coveredId.startsWith("pyr_")) return false
    val (cr, cc) = parsePyramidId(coverId)
    val (pr, pc) = parsePyramidId(coveredId)
    if (cr != pr + 1) return false
    // A card at (pr,pc) is covered by (pr+1, pc) and (pr+1, pc+1).
    val below = state.pyramid[pr + 1]
    return when (cc) {
        pc -> below.getOrNull(pc + 1) == null       // cover is the left child; right one gone
        pc + 1 -> below.getOrNull(pc) == null       // cover is the right child; left one gone
        else -> false
    }
}

/** Whether the two cards may be removed together (ranks sum to 13). */
internal fun SolitaireViewModel.canPyramidRemovePair(state: PyramidState, idA: String, idB: String): Boolean {
    if (idA == idB) return false
    val a = pyramidCardAt(state, idA) ?: return false
    val b = pyramidCardAt(state, idB) ?: return false
    if (a.rank.value + b.rank.value != PAIR_TARGET) return false
    return isPairRemovable(state, idA, idB)
}

private fun SolitaireViewModel.isPairRemovable(state: PyramidState, idA: String, idB: String): Boolean {
    val aExposed = isPyramidPlayable(state, idA)
    val bExposed = isPyramidPlayable(state, idB)
    if (aExposed && bExposed) return true
    // Partially-covered variation (always allowed): one card is exposed and
    // is the sole remaining cover of the other.
    if (aExposed && isPyramidSoleCover(state, idA, idB)) return true
    return bExposed && isPyramidSoleCover(state, idB, idA)
}

internal fun SolitaireViewModel.parsePyramidId(id: String): Pair<Int, Int> {
    val parts = id.removePrefix("pyr_").split("_")
    return parts[0].toInt() to parts[1].toInt()
}

internal fun SolitaireViewModel.pyramidCardAt(state: PyramidState, id: String): Card? = when {
    id == "waste" -> state.waste.lastOrNull()
    id.startsWith("pyr_") -> {
        val (r, c) = parsePyramidId(id)
        state.pyramid.getOrNull(r)?.getOrNull(c)
    }
    else -> null
}

/** Remove the card identified by [id] from the pyramid or the waste. */
internal fun SolitaireViewModel.pyramidRemove(state: PyramidState, id: String): PyramidState = when {
    id == "waste" -> state.copy(waste = state.waste.dropLast(1))
    id.startsWith("pyr_") -> {
        val (r, c) = parsePyramidId(id)
        val newPyramid = state.pyramid.map { it.toMutableList() }
        newPyramid[r][c] = null
        state.copy(pyramid = newPyramid.map { it.toList() })
    }
    else -> state
}

/**
 * Tap a card in the pyramid or on the waste. If it forms a valid pair with
 * the current selection (both exposed, or an exposed card plus a card it is
 * the sole cover of), both are removed. Kings (value 13) are removed on their
 * own. Otherwise an exposed tap becomes the new selection.
 */
internal fun SolitaireViewModel.pyramidTapCardImpl(id: String) {
    val state = uiStateInternal.value.pyramid ?: return
    if (state.isWon) return
    pyramidCardAt(state, id) ?: return

    if (tryRemovePair(state, id)) return
    selectOrClearKing(state, id)
}

private fun SolitaireViewModel.tryRemovePair(state: PyramidState, id: String): Boolean {
    // If a selection exists and this tap completes a pair, remove both.
    val selected = state.selectedId
    if (selected == null || !canPyramidRemovePair(state, selected, id)) return false
    saveHistory()
    val afterFirst = pyramidRemove(state, id)
    val afterSecond = pyramidRemove(afterFirst, selected).copy(
        selectedId = null,
        moveCount = state.moveCount + 1
    )
    uiStateInternal.update { it.copy(pyramid = afterSecond) }
    checkPyramidWin()
    return true
}

private fun SolitaireViewModel.selectOrClearKing(state: PyramidState, id: String) {
    // Otherwise the tapped card must be exposed to select it or remove a King.
    if (!isPyramidPlayable(state, id)) return
    val card = pyramidCardAt(state, id) ?: return
    if (card.rank.value == Rank.KING.value) {
        removeSingleKing(state, id)
        return
    }
    uiStateInternal.update {
        it.copy(pyramid = state.copy(selectedId = if (state.selectedId == id) null else id))
    }
}

private fun SolitaireViewModel.removeSingleKing(state: PyramidState, id: String) {
    saveHistory()
    val removed = pyramidRemove(state, id).copy(
        selectedId = null,
        moveCount = state.moveCount + 1
    )
    uiStateInternal.update { it.copy(pyramid = removed) }
    checkPyramidWin()
}

internal fun SolitaireViewModel.pyramidDealStockImpl() {
    val state = uiStateInternal.value.pyramid ?: return
    if (state.isWon) return
    if (state.stock.isEmpty()) {
        // Original mode is a single pass (no recycle); relaxed is unlimited.
        if (!state.relaxed || state.waste.isEmpty()) return
        saveHistory()
        uiStateInternal.update {
            it.copy(pyramid = state.copy(
                stock = state.waste.reversed(),
                waste = emptyList(),
                selectedId = null,
                moveCount = state.moveCount + 1
            ))
        }
        return
    }
    saveHistory()
    uiStateInternal.update {
        it.copy(pyramid = state.copy(
            stock = state.stock.dropLast(1),
            waste = state.waste + state.stock.last(),
            // Dealing replaces the waste top, so a "waste" selection
            // would silently retarget to the new card. Drop it (a
            // pyramid selection names a fixed card and survives).
            selectedId = if (state.selectedId == "waste") null else state.selectedId,
            moveCount = state.moveCount + 1
        ))
    }
}

internal fun SolitaireViewModel.checkPyramidWin() {
    val state = uiStateInternal.value.pyramid ?: return
    if (state.pyramid.all { row -> row.all { it == null } }) {
        uiStateInternal.update { it.copy(pyramid = state.copy(isWon = true)) }
        onGameWon(GameMode.PYRAMID, state.elapsedSeconds, state.usedUndo)
    }
}
