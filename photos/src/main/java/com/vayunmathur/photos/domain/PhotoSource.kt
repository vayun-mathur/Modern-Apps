package com.vayunmathur.photos.domain

import kotlinx.serialization.Serializable

/**
 * Identifies the list a viewer was opened from, so [com.vayunmathur.photos.Route.PhotoPage] can
 * re-derive its paging list from the view-model instead of carrying a
 * serialized copy of every [com.vayunmathur.photos.data.Photo] in that list
 * (which was retained in memory and restored stale after process death).
 */
@Serializable
sealed interface PhotoSource {
    /** The full gallery grid (also the ACTION_VIEW viewer, which swipes the library). */
    @Serializable
    data object Gallery : PhotoSource

    /** The current search results in the gallery view-model. */
    @Serializable
    data object Search : PhotoSource

    /** The favorites collection. */
    @Serializable
    data object Favorites : PhotoSource

    /** One album, identified by name (the key the albums flow groups by). */
    @Serializable
    data class Album(val albumName: String) : PhotoSource

    /** One person cluster, identified by cluster id. */
    @Serializable
    data class Person(val clusterId: Long) : PhotoSource

    /** One map cluster, identified by its cover photo id in the map view-model. */
    @Serializable
    data class MapCluster(val coverPhotoId: Long) : PhotoSource
}
