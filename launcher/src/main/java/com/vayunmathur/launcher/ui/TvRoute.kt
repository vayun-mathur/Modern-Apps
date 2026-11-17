package com.vayunmathur.launcher.ui

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The TV nav graph is a single destination.
 *
 * Named TvRoute (not Route): the phone graph's `Route` lives in this same module, and two
 * same-named sealed interfaces would collide. Same reason for [TvNavigation].
 *
 * The edit menu (move/replace/remove) and the add-app picker are **not** routes: they are
 * overlays inside the home screen, because D-pad focus must stay inside one focus tree
 * while editing.
 */
@Serializable
sealed interface TvRoute : NavKey {
    /** The banner grid with the clock header and bottom row. */
    @Serializable
    data object Home : TvRoute
}
