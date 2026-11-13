package com.vayunmathur.games.voxels.domain

/** The in-game layer the system Back gesture acts on. */
enum class BackTarget {
    /** Close the open chest (and tell the engine it is closed). */
    CHEST,
    /** Close the villager trade screen. */
    TRADE,
    /** Close the furnace screen. */
    FURNACE,
    /** Close the stonecutter screen. */
    STONECUTTER,
    /** Close the inventory / crafting screen. */
    INVENTORY,
    /** Leave the pause menu and resume. */
    RESUME,
    /** Nothing is open: open the pause menu instead of quitting the world. */
    PAUSE,
}

/** Which overlays are currently showing over the world. */
data class OverlayFlags(
    val chest: Boolean = false,
    val trade: Boolean = false,
    val furnace: Boolean = false,
    val stonecutter: Boolean = false,
    val inventory: Boolean = false,
    val paused: Boolean = false,
)

/**
 * Back closes the top-most overlay; with none open it pauses. It never finishes the activity:
 * leaving the world is the pause menu's explicit "Save & Quit", so a stray edge swipe while
 * looking around can no longer drop the player out of their world.
 */
fun backTarget(open: OverlayFlags): BackTarget = when {
    open.chest -> BackTarget.CHEST
    open.trade -> BackTarget.TRADE
    open.furnace -> BackTarget.FURNACE
    open.stonecutter -> BackTarget.STONECUTTER
    open.inventory -> BackTarget.INVENTORY
    open.paused -> BackTarget.RESUME
    else -> BackTarget.PAUSE
}
