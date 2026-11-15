package com.vayunmathur.photos.ui

import com.vayunmathur.library.util.NavKey
import com.vayunmathur.photos.data.DrawingTool
import kotlinx.serialization.Serializable

/** Contract for edit route. */
@Serializable
sealed interface EditRoute : NavKey {
    /** Immutable value describing edit photo; carries id, uri. */
    @Serializable
    data class EditPhoto(val id: Long, val uri: String? = null) : EditRoute

    /** Immutable value describing drawing settings. */
    @Serializable
    data class DrawingSettings(
        val tool: DrawingTool,
        val currentColor: Int,
        val currentThickness: Float,
        val currentOpacity: Float
    ) : EditRoute
}
