package com.vayunmathur.share

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding share state and operations. */
    @Serializable
    data object Share : Route
}
