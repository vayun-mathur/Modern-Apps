package com.vayunmathur.games.solitaire

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.games.solitaire.data.GameMode
import kotlinx.serialization.Serializable

/** Navigation destinations for the solitaire app. */
@Serializable
sealed interface Route : NavKey {
    /** Home destination listing the available solitaire variants. */
    @Serializable
    data object Home : Route
    /** Game destination for the variant in [mode]. */
    @Serializable
    data class Game(val mode: GameMode) : Route
    /** Game Center destination showing achievements and stats. */
    @Serializable
    data object GameCenter : Route
    /** Settings destination for solitaire preferences. */
    @Serializable
    data object Settings : Route
}
