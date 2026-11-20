package com.vayunmathur.uniflix

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding home state and operations. */
    @Serializable
    data object Home : Route

    /** Singleton holding movies state and operations. */
    @Serializable
    data object Movies : Route

    /** Singleton holding series state and operations. */
    @Serializable
    data object Series : Route

    /** Singleton holding search state and operations. */
    @Serializable
    data object Search : Route

    /** Immutable value describing detail; carries mediaType, tmdbId. */
    @Serializable
    data class Detail(val mediaType: String, val tmdbId: Int) : Route

    /** Immutable value describing watch; carries mediaType, tmdbId, season, episode. */
    @Serializable
    data class Watch(val mediaType: String, val tmdbId: Int, val season: Int = 0, val episode: Int = 0) : Route

    /** Singleton holding lists state and operations. */
    @Serializable
    data object Lists : Route

    /** Singleton holding continue watching state and operations. */
    @Serializable
    data object ContinueWatching : Route

    /** Singleton holding addons state and operations. */
    @Serializable
    data object Addons : Route

    /** Immutable value describing addon detail; carries addonDbId. */
    @Serializable
    data class AddonDetail(val addonDbId: Long) : Route
}
