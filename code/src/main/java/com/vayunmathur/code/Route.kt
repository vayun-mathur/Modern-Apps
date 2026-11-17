package com.vayunmathur.code

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the Code editor workspace. */
@Serializable
sealed interface Route : NavKey {
    /** Main code-editor destination. */
    @Serializable
    data object Editor : Route

    /** Editor settings destination. */
    @Serializable
    data object Settings : Route

    /** Project-wide search destination. */
    @Serializable
    data object Search : Route

    /** One directory level of the folder picker; Back pops one level. */
    @Serializable
    data class FolderBrowser(val path: String) : Route

    /** Git panel destination. */
    @Serializable
    data object Git : Route

    /** Terminal destination. */
    @Serializable
    data object Terminal : Route

    /** File preview destination. */
    @Serializable
    data object Preview : Route

    /** Snippets destination. */
    @Serializable
    data object Snippets : Route
}
