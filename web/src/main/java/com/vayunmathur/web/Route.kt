package com.vayunmathur.web

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding shields state and operations. */
    @Serializable data object Browser : Route
    /** Browsing history route. */
    @Serializable data object History : Route
    /** Bookmarks route. */
    @Serializable data object Bookmarks : Route
    /** Browser settings route. */
    @Serializable data object Settings : Route
    /** Downloads route. */
    @Serializable data object Downloads : Route
    /** Per-site data management route. */
    @Serializable data object SiteData : Route
    /** Installed sites (PWAs) route. */
    @Serializable data object InstalledSites : Route
    /** Shields settings route. */
    @Serializable data object Shields : Route
}
