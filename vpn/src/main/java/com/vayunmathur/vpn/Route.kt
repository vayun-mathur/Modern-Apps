package com.vayunmathur.vpn

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** The Tunnels tab: a sibling destination switched with `backStack.reset`. */
    @Serializable
    data object Tunnels : Route

    /**
     * The Logging tab: a sibling destination switched with `backStack.reset`.
     * [tab] is the in-screen leaderboard tab the Logging screen last showed, so
     * leaving and returning to Logging restores it; the sub-tabs themselves stay
     * in-screen state of the Logging destination (see `LoggingContent`).
     */
    @Serializable
    data class Logging(val tab: Int = 0) : Route

    /** The Settings tab: a sibling destination switched with `backStack.reset`. */
    @Serializable
    data object Settings : Route

    /** Immutable value describing detail; carries id. */
    @Serializable
    data class Detail(val id: Long) : Route

    /** Singleton holding bypass list state and operations. */
    @Serializable
    data object BypassList : Route
}
