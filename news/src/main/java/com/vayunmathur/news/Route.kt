package com.vayunmathur.news

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the news app. */
sealed interface Route : NavKey {
    @Serializable
    /** Merged, newest-first feed across enabled sources. */
    data object Feed : Route

    @Serializable
    /** Clean reader (or original web view) for the article at [url]. */
    data class Reader(val url: String) : Route

    @Serializable
    /** Enable/disable built-in sources. */
    data object Sources : Route

    @Serializable
    /** Bookmarked articles, readable offline. */
    data object Saved : Route
}
