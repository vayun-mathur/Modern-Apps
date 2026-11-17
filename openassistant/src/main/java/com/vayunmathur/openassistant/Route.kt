package com.vayunmathur.openassistant

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route: NavKey {
    /** Immutable value describing conversation page; carries id. */
    @Serializable
    data class ConversationPage(val id: Long): Route
    /** Singleton holding settings page state and operations. */
    @Serializable
    data object SettingsPage: Route
}
