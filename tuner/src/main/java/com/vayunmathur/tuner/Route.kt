package com.vayunmathur.tuner

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding tuner state and operations. */
    @Serializable
    data object Tuner : Route
}
