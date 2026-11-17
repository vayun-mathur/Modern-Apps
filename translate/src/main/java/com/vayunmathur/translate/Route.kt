package com.vayunmathur.translate

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for Translate. */
@Serializable
sealed interface Route : NavKey {
    /** Text translation destination. */
    @Serializable
    data object Text : Route

    /** Live camera translation destination. */
    @Serializable
    data object Camera : Route

    /** Image translation for the given image URI. */
    @Serializable
    data class Image(val imageUri: String) : Route

    /** Language picker for the source or target language. */
    @Serializable
    data class LanguagePicker(val forSource: Boolean) : Route

    /** Translation history destination. */
    @Serializable
    data object History : Route
}
