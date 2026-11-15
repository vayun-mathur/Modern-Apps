package com.vayunmathur.passwords

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding menu state and operations. */
    @Serializable
    data object Menu : Route

    /** Immutable value describing password page; carries id. */
    @Serializable
    data class PasswordPage(val id: Long) : Route

    /** Immutable value describing password edit page; carries id. */
    @Serializable
    data class PasswordEditPage(val id: Long) : Route

    /** Immutable value describing passkey page; carries id. */
    @Serializable
    data class PasskeyPage(val id: Long) : Route

    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}
