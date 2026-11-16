package com.vayunmathur.madeveloper

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for MA Developer. */
sealed interface Route : NavKey {
    @Serializable
    /** Home: repositories, CI summary and push status. */
    data object Home : Route

    @Serializable
    /** One repository: refs, issues, pull requests and file tree. */
    data class Repo(val name: String) : Route

    @Serializable
    /** One file in a repository, shown with line numbers. */
    data class File(val repo: String, val path: String) : Route

    @Serializable
    /** Full CI history from the git host tests page. */
    data object Tests : Route

    @Serializable
    /** Read-only catalog of available builds from the F-Droid repo. */
    data object Catalog : Route

    @Serializable
    /** Pairing and server-pushed installs. */
    data object Push : Route
}
