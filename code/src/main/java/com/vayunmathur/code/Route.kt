package com.vayunmathur.code

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Editor : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data object Search : Route

    /** One directory level of the folder picker; Back pops one level. */
    @Serializable
    data class FolderBrowser(val path: String) : Route

    @Serializable
    data object Git : Route

    @Serializable
    data object Terminal : Route

    @Serializable
    data object Preview : Route

    @Serializable
    data object Snippets : Route
}
