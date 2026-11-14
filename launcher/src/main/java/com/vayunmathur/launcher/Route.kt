package com.vayunmathur.launcher

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The nav graph is deliberately small.
 *
 * The app drawer, an open folder, the long-press item menu and the widget picker sheet are **not**
 * routes: they are overlays inside [com.vayunmathur.launcher.ui.HomeContent], because a drag has to
 * be able to cross from the drawer onto the grid, out of a folder onto the grid, and out of an
 * item's menu onto the grid, and it can only do that while everything stays inside the home
 * screen's single gesture owner. Only the screens a drag never touches are destinations.
 */
@Serializable
sealed interface Route : NavKey {
    /** The paged home screen, with the drawer, folders and item menus layered over it. */
    @Serializable
    data object Home : Route

    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}
