package com.vayunmathur.vpn

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Immutable value describing main; carries initialTab. */
    @Serializable
    data class Main(val initialTab: Int = 0) : Route

    /** Immutable value describing detail; carries id. */
    @Serializable
    data class Detail(val id: Long) : Route

    /** Singleton holding bypass list state and operations. */
    @Serializable
    data object BypassList : Route
}
