package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for video edit route. */
@Serializable
sealed interface VideoEditRoute : NavKey {
    /** Immutable value describing edit video; carries id, uri. */
    @Serializable
    data class EditVideo(val id: Long, val uri: String? = null) : VideoEditRoute
}
