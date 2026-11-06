package com.vayunmathur.games.logicgate

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations. */
@Serializable
sealed interface Route : NavKey {
    /** The level map (Computer tab). */
    @Serializable data object Progression : Route

    /** The circuit editor for one level. */
    @Serializable data class Game(val levelId: String) : Route

    /** The player's components (Sandbox tab). */
    @Serializable data object Sandbox : Route

    /** The editor for one sandbox component. */
    @Serializable data class Component(val componentId: String) : Route

    /** Achievements. */
    @Serializable data object GameCenter : Route
}
