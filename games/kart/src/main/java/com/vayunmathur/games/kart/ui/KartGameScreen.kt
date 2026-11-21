package com.vayunmathur.games.kart.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.games.kart.R
import com.vayunmathur.games.kart.platform.KartGameActions
import com.vayunmathur.games.kart.platform.KartUiState
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.library.ui.AppBarAlignment
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The garage and the race, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`.
 */
@Composable
fun KartGameScreen(
    state: KartUiState,
    phase: RacePhase,
    actions: KartGameActions,
) {
    AppScaffold(
        title = state.cup?.let { stringResource(R.string.cup_title, it.name) }
            ?: stringResource(R.string.app_name),
        alignment = AppBarAlignment.Center,
    ) { padding ->
        // Only the garage and the results scroll. Countdown and the race itself
        // are the live race surface: a scrollable column there is a hazard while
        // racing (a stray swipe drags the viewport and HUD away mid-race), so
        // those phases get a fixed layout sized to the window instead.
        val scrollState = rememberScrollState()
        val scrollable = phase is RacePhase.Lobby || phase is RacePhase.Results
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                .padding(horizontal = Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            when (val p = phase) {
                is RacePhase.Lobby -> LobbyBody(state, p, actions)
                is RacePhase.Countdown -> CountdownBody(p)
                is RacePhase.Racing -> RacingBody(p)
                is RacePhase.Results -> ResultsBody(p, actions)
            }
        }
    }
}

@Composable
private fun LobbyBody(
    state: KartUiState,
    phase: RacePhase.Lobby,
    actions: KartGameActions,
) {
    val cup = state.cup
    if (cup == null) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.xxl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.garage_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Text(
            pluralStringResource(R.plurals.cup_tracks, cup.trackCount, cup.trackCount),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (state.progress.racesWon > 0) {
            Text(
                pluralStringResource(R.plurals.races_won, state.progress.racesWon, state.progress.racesWon),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val seats = phase.lobby.slots.size
        if (seats > 0) {
            Text(
                pluralStringResource(R.plurals.lobby_seats, seats, seats),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = { actions.startRace() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.start_race)) }
        Text(
            stringResource(R.string.how_to_play),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CountdownBody(phase: RacePhase.Countdown) {
    Text(
        "${phase.secondsLeft}",
        style = MaterialTheme.typography.displayLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Text(
        stringResource(R.string.countdown_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ColumnScope.RacingBody(phase: RacePhase.Racing) {
    // The native 3D viewport sits behind the standings HUD. With no
    // libkart_engine it is never composed and the HUD is the whole screen;
    // in a @Preview (Layoutlib) a SurfaceView is a blank rect, so listing
    // images keep rendering the HUD exactly as before.
    // The viewport takes whatever height the HUD leaves (weight, not a fixed
    // height): the race layout never scrolls, so on a short landscape window
    // the 3D view shrinks instead of pushing the standings off-screen.
    if (com.vayunmathur.games.kart.util.KartNative.isAvailable) {
        AndroidView(
            factory = { ctx ->
                // No opaque background: a SurfaceView shows its Vulkan layer
                // through a transparent hole in the window.
                KartSurfaceView(ctx)
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
    Text(
        stringResource(R.string.race_lap, phase.playerLap + 1, phase.totalLaps),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    val lastLap = phase.lastLapMs?.let(::formatLapTime)
    if (lastLap != null) {
        Text(
            stringResource(R.string.race_last_lap, lastLap),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val bestLap = phase.bestLapMs?.let(::formatLapTime)
    if (bestLap != null) {
        Text(
            stringResource(R.string.race_best_lap, bestLap),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // One line per kart (name beside its progress bar, not above it): the race
    // layout never scrolls, so the whole HUD has to fit the window height —
    // on a ~360dp landscape phone the stacked two-line rows pushed the last
    // places off-screen.
    phase.order.forEachIndexed { place, id ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.race_place, place + 1, displayName(id)),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { phase.lapProgress[id] ?: 0f },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Format a lap time as m:ss.mmm, e.g. 0:24.318. Null-safe at the call site: only called non-null. */
private fun formatLapTime(ms: Long): String {
    val total = ms.coerceAtLeast(0L)
    val minutes = total / 60_000
    val seconds = (total % 60_000) / 1_000
    val millis = total % 1_000
    return "$minutes:%02d.%03d".format(java.util.Locale.US, seconds, millis)
}

/** Short label for a seat: players keep their join name, AI karts show as rivals. */
private fun displayName(id: String): String = if (id.startsWith("ai-")) "Rival ${id.removePrefix("ai-")}" else "You"

@Composable
private fun ResultsBody(phase: RacePhase.Results, actions: KartGameActions) {
    Text(
        stringResource(R.string.results_title),
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    phase.order.forEachIndexed { place, id ->
        Text(
            stringResource(R.string.race_place, place + 1, displayName(id)),
            style = MaterialTheme.typography.bodyMedium,
            color = if (id == phase.winner) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
    OutlinedButton(
        onClick = { actions.backToLobby() },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(R.string.back_to_lobby)) }
}
