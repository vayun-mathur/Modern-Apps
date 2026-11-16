package com.vayunmathur.globalesim

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Purchase tab (sibling route): your eSIM, balance and top-ups. */
    @Serializable
    data object Purchase : Route

    /** Pricing tab (sibling route): searchable per-country rates. */
    @Serializable
    data object Pricing : Route
}
