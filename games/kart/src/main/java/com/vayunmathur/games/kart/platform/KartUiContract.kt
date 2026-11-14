package com.vayunmathur.games.kart.platform

import com.vayunmathur.games.kart.data.KartCup
import com.vayunmathur.games.kart.data.KartProgress

/**
 * The UI contract between [KartViewModel] and the garage screen.
 *
 * The screen takes this interface rather than the ViewModel itself so it can be rendered by a
 * `@Preview` — which is what the store listing images are generated from. It lives in `platform`
 * rather than next to the screen so the dependency runs one way: `ui` depends on `platform`, and
 * the ViewModel implements this.
 *
 * Every member has a no-op default, so `Noop` is the whole implementation a preview needs.
 */
interface KartGameActions {
    /** Starts the lobby countdown; the tick loop banks the cup clear when the race finishes. */
    fun startRace() {}

    /** Back to the lobby from the results screen. */
    fun backToLobby() {}

    /** Shared constants and helpers for KartGameActions. */
    companion object { val Noop: KartGameActions = object : KartGameActions {} }
}

/** What the garage screen needs to draw itself. */
data class KartUiState(
    val cup: KartCup? = null,
    val progress: KartProgress = KartProgress(),
)
