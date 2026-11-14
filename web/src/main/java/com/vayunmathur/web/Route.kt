package com.vayunmathur.web

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding shields state and operations. */
    @Serializable data object Browser : Route
    @Serializable data object History : Route
    @Serializable data object Bookmarks : Route
    @Serializable data object Settings : Route
    @Serializable data object Downloads : Route
    @Serializable data object SiteData : Route
    @Serializable data object InstalledSites : Route
    @Serializable data object Shields : Route
}
