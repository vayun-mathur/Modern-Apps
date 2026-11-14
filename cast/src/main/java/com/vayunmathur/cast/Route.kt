package com.vayunmathur.cast

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation destinations for the cast app. */
@Serializable
sealed interface Route : NavKey {
    /** The only screen: device list, source picker and transport, in one place. */
    @Serializable
    data object Cast : Route
}
