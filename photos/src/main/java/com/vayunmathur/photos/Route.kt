package com.vayunmathur.photos

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Identifies the list a viewer was opened from, so [Route.PhotoPage] can
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

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Singleton holding gallery state and operations. */
    @Serializable
    data object Gallery: Route

    /** Viewer for [id]; [sourceKey] re-derives the swiping list, [pendingUri] is an unindexed ACTION_VIEW uri. */
    @Serializable
    data class PhotoPage(val id: Long, val sourceKey: PhotoSource?, val pendingUri: String? = null): Route

    /** Singleton holding map state and operations. */
    @Serializable
    data object Map: Route

    /** Singleton holding people state and operations. */
    @Serializable
    data object People: Route

    /** Singleton holding albums state and operations. */
    @Serializable
    data object Albums: Route

    /** Immutable value describing album detail; carries albumName. */
    @Serializable
    data class AlbumDetail(val albumName: String): Route

    /** The Favorites album: every MediaStore favorite, opened from its Albums tile. */
    @Serializable
    data object Favorites: Route

    /** Singleton holding trash state and operations. */
    @Serializable
    data object Trash: Route

    /** Singleton holding secure folder state and operations. */
    @Serializable
    data object SecureFolder: Route

    /** Immutable value describing vault viewer; carries vaultId. */
    @Serializable
    data class VaultViewer(val vaultId: Long): Route

    /** Immutable value describing wallpaper; carries id, uri. */
    @Serializable
    data class Wallpaper(val id: Long, val uri: String? = null) : Route
}
