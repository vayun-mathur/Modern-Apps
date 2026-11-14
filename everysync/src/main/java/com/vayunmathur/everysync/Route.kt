package com.vayunmathur.everysync

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for EverySync. */
sealed interface Route : NavKey {
    /** Destination showing configured sync accounts. */
    @Serializable data object Accounts : Route
    /** Destination for adding a new sync account. */
    @Serializable data object AddAccount : Route
    /** Destination showing one account's sync detail by name. */
    @Serializable data class AccountDetail(val accountName: String) : Route
    /** Destination showing EverySync settings. */
    @Serializable data object Settings : Route
}
