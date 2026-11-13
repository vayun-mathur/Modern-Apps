package com.vayunmathur.games.sudoku.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/**
 * Calls [onTick] once a second while [running] is true and the screen is RESUMED.
 *
 * A plain `LaunchedEffect` loop keeps going for as long as the composition exists, and the
 * composition outlives the screen being visible: with the app in the background, behind the
 * recents switcher or under the notification shade the clock kept counting, inflating times and
 * costing the speed achievement. Keying the loop on the lifecycle stops it at ON_PAUSE and starts
 * it again at ON_RESUME. A partial second is dropped on each pause, which errs in the player's
 * favour.
 */
@Composable
internal fun GameClock(running: Boolean, onTick: () -> Unit) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val ticking = running && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val tick by rememberUpdatedState(onTick)
    LaunchedEffect(ticking) {
        while (ticking) {
            delay(TICK_MILLIS)
            tick()
        }
    }
}

private const val TICK_MILLIS = 1_000L
