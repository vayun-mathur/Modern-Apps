package com.vayunmathur.games.voxels

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Typed destinations for the menu and the game.
 *
 * The menu ([com.vayunmathur.games.voxels.platform.MenuActivity]) hosts
 * [Worlds] and [CreateWorld] on a back stack; playing a world still
 * starts the game Activity by Intent.
 *
 * Inside the game, [Game] is the root of a second stack and the
 * full-screen screens that used to be six `*Open` booleans are overlay
 * destinations pushed over it: [Inventory], [Furnace], [Chest],
 * [Stonecutter], [Trade] and [Settings]. Back pops the top overlay, so
 * the old hand-encoded back priority is now just stack order. The
 * pause menu, the loading phase and the HUD are not destinations —
 * they stay plain screen state inside the game. Routes carry ids and
 * small arguments, never whole objects.
 */
@Serializable
sealed interface Route : NavKey {
    /** The saved-world list: the menu's root destination. */
    @Serializable
    data object Worlds : Route

    /** The world creator, pushed over [Worlds]; Back returns to the list. */
    @Serializable
    data object CreateWorld : Route

    /** The live world (Vulkan surface + HUD): the game's root destination. */
    @Serializable
    data class Game(val worldId: String) : Route

    /** Inventory / crafting overlay; [startTab] selects its opening tab. */
    @Serializable
    data class Inventory(val startTab: Int = 0) : Route

    /** Furnace overlay; [isBlast] unlocks the blast-furnace alloy recipes. */
    @Serializable
    data class Furnace(val isBlast: Boolean) : Route

    /** Chest / container overlay over the world. */
    @Serializable
    data object Chest : Route

    /** Stonecutter overlay over the world. */
    @Serializable
    data object Stonecutter : Route

    /** Villager trade overlay over the world. */
    @Serializable
    data object Trade : Route

    /** In-game settings sheet over the world; the world holds still while it is up. */
    @Serializable
    data object Settings : Route
}
