package com.vayunmathur.news

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the news app. */
sealed interface Route : NavKey {
    @Serializable
    /** Merged feed across all sources (Top stories by default). */
    data object Feed : Route

    @Serializable
    /** Story page for the story with [storyId] (see domain Story.id). */
    data class Story(val storyId: String) : Route

    @Serializable
    /** Clean reader (or original web view) for the article at [url]. */
    data class Reader(val url: String) : Route

    @Serializable
    /** Bookmarked articles, readable offline. */
    data object Saved : Route

    @Serializable
    /** Picker for the manually chosen Local news location preference. */
    data object LocalLocation : Route

    @Serializable
    /** News settings (holds the Local news location preference). */
    data object Settings : Route
}
