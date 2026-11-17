package com.vayunmathur.photos

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.photos.data.Photo
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Singleton holding gallery state and operations. */
    @Serializable
    data object Gallery: Route

    /** Immutable value describing photo page; carries id, overridePhotosList, pendingUri. */
    @Serializable
    data class PhotoPage(val id: Long, val overridePhotosList: List<Photo>?, val pendingUri: String? = null): Route

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
