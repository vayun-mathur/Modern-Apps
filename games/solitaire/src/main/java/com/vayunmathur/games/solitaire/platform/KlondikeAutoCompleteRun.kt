package com.vayunmathur.games.solitaire.platform

import com.vayunmathur.games.solitaire.data.KlondikeState
import kotlinx.coroutines.flow.update

// ---- Klondike auto-complete: the run on the view model ----
// Planning is pure and lives in KlondikeAutoComplete.kt; this drives it on live state.

/** Instant auto-complete: applies the whole plan in one step (one history entry). */
internal fun SolitaireViewModel.klondikeAutoCompleteImpl() {
    val state = uiStateInternal.value.klondike ?: return
    val finished = planKlondikeAutoComplete(state)?.lastOrNull()?.after ?: return
    saveHistory()
    uiStateInternal.update { it.copy(klondike = finished) }
    checkKlondikeWin()
}

/**
 * Animated auto-complete entry: the button tap (and re-tap) lands here. Merely
 * offering the button locks nothing; only a started run pauses board input, and
 * board input then stops the run ([stopAutoComplete]). Re-tapping the button
 * mid-run skips straight to the finished board. The run is only started for a
 * board [planKlondikeAutoComplete] finishes, so it can never strand itself at the
 * end of the stock. One history entry covers the whole run, so a single undo
 * restores the pre-run board, and the win is checked once at the end so the
 * overlay cannot pop mid-flight. Flights already in the air keep flying.
 */
internal fun SolitaireViewModel.requestAutoCompleteImpl() {
    if (autoCompleteRunningInternal.value) { skipAutoCompleteToEnd(); return }
    val state = uiStateInternal.value.klondike ?: return
    if (!canKlondikeAutoComplete(state)) return
    saveHistory()
    queuedTaps.clear()
    autoCompleteRunningInternal.value = true
    advanceAutoComplete()
}

/**
 * Steps the run forward from the live state with [nextKlondikeAutoCompleteStep] — the
 * same function the plan behind the offer is made of — applying each step to game state
 * at once. A foundation hop whose card has a known position launches a fast flight and
 * the run waits for its landing; draws, recycles and hops of never-laid-out cards apply
 * instantly. A hop whose card is still in the air from a player's move waits for that
 * flight to land first, so the card is never drawn twice.
 */
internal fun SolitaireViewModel.advanceAutoComplete() {
    while (autoCompleteRunningInternal.value) {
        when (takeAutoCompleteStep()) {
            StepOutcome.APPLIED -> Unit
            StepOutcome.WAITING -> return
            StepOutcome.DONE -> break
        }
    }
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
    checkKlondikeWin()
}

private enum class StepOutcome { APPLIED, WAITING, DONE }

/** Applies the next step to game state, flying it when it is a hop of a laid-out card. */
private fun SolitaireViewModel.takeAutoCompleteStep(): StepOutcome {
    val current = uiStateInternal.value.klondike
    val step = current?.let { nextKlondikeAutoCompleteStep(it) } ?: return StepOutcome.DONE
    if (step is AutoCompleteStep.ToFoundation && isAirborne(hopSource(current, step))) return StepOutcome.WAITING
    uiStateInternal.update { it.copy(klondike = step.after) }
    // Turned face down: their laid-out positions no longer mean anything.
    if (step is AutoCompleteStep.Recycle) current.waste.forEach { cardPositions.remove(it) }
    return if (step is AutoCompleteStep.ToFoundation && flyHop(step)) StepOutcome.WAITING else StepOutcome.APPLIED
}

/** Launches the fast flight for [step]; false when its card was never laid out. */
private fun SolitaireViewModel.flyHop(step: AutoCompleteStep.ToFoundation): Boolean {
    val from = cardPositions[step.card] ?: return false
    val flight = launchFlight(
        cards = listOf(step.card),
        from = from,
        targetId = "$FOUNDATION_PREFIX${step.foundationIndex}",
        cardSize = lastCardSize,
        fast = true,
    ) ?: return false
    autoCompleteFlightId = flight.id
    return true
}

/** Where a hop's card sits before the hop, as a pile range. */
private fun hopSource(state: KlondikeState, step: AutoCompleteStep.ToFoundation): PileRange =
    if (step.sourceId == WASTE) {
        PileRange(WASTE, state.waste.lastIndex, listOf(step.card))
    } else {
        val col = step.sourceId.removePrefix(TABLEAU_PREFIX).substringBefore("_")
        PileRange("$TABLEAU_PREFIX$col", step.sourceId.substringAfterLast("_").toInt(), listOf(step.card))
    }

/**
 * Stops a running auto-complete where it is: every step taken so far stays applied (one
 * undo still restores the pre-run board), the hop in the air lands, and the board is
 * playable again with the offer back if it can still finish. A tap, drag or stock tap
 * on the board mid-run lands here.
 */
internal fun SolitaireViewModel.stopAutoComplete() {
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
}

/**
 * Skips the animation: applies the rest of the plan on the live state at once. Hops
 * already in the air are left to land — their moves are applied already.
 */
internal fun SolitaireViewModel.skipAutoCompleteToEnd() {
    autoCompleteRunningInternal.value = false
    autoCompleteFlightId = null
    val state = uiStateInternal.value.klondike ?: return
    uiStateInternal.update { it.copy(klondike = finishedKlondikeAutoComplete(state)) }
    checkKlondikeWin()
}
