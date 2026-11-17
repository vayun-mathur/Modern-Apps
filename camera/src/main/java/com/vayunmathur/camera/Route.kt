package com.vayunmathur.camera

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the camera app. */
sealed interface Route : NavKey {
    @Serializable
    /** The main camera capture screen. */
    data object Camera : Route
    @Serializable
    /** Camera settings screen. */
    data object Settings : Route
}
