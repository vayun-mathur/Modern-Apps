package com.vayunmathur.games.voxels

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Typed destinations for the menu and the game.
 *
 * The menu ([com.vayunmathur.games.voxels.platform.MenuActivity]) hosts
 * [Worlds] and [CreateWorld] on a back stack; playing a world still
 * starts the game Activity by Intent, so no game destination lives here
 * yet — the in-game overlays join this type with the game commit.
 * Routes carry ids and small arguments, never whole objects.
 */
@Serializable
sealed interface Route : NavKey {
    /** The saved-world list: the menu's root destination. */
    @Serializable
    data object Worlds : Route

    /** The world creator, pushed over [Worlds]; Back returns to the list. */
    @Serializable
    data object CreateWorld : Route
}
