package com.vayunmathur.madeveloper

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for Developer. */
sealed interface Route : NavKey {
    @Serializable
    /** Home: repositories, CI summary and push status. */
    data object Home : Route

    @Serializable
    /** One repository: branches, recent commits and file tree. */
    data class Repo(val name: String) : Route

    @Serializable
    /** Commits of one repository on one branch. */
    data class Commits(val repo: String, val branch: String) : Route

    @Serializable
    /** One commit's metadata; the JSON API has no diff endpoint. */
    data class Commit(val repo: String, val branch: String, val sha: String) : Route

    @Serializable
    /** One file in a repository on a branch, shown with line numbers. */
    data class File(val repo: String, val path: String, val branch: String) : Route

    @Serializable
    /** Open issues across hosted repos, from the git host issues page. */
    data object Issues : Route

    @Serializable
    /** Open pull requests across hosted repos, from the git host PR page. */
    data object Prs : Route

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
