package com.vayunmathur.globalesim

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Tab host: Purchase + Pricing. */
    @Serializable
    data object Tabs : Route
}
