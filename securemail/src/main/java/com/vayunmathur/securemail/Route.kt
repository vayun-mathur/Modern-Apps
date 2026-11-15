package com.vayunmathur.securemail

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Holds a pending deep-link/share route until Navigation consumes it. */
object IntentState {
    var navigationRoute: Route? = null
}

/** Contract for route. */
@Serializable
sealed interface Route : NavKey {
    /** Singleton holding onboarding state and operations. */
    @Serializable
    data object Onboarding : Route

    /** Singleton holding lock state and operations. */
    @Serializable
    data object Lock : Route

    /** Singleton holding inbox state and operations. */
    @Serializable
    data object Inbox : Route

    /** Immutable value describing composer. */
    @Serializable
    data class Composer(
        val to: String = "",
        val subject: String = "",
        val body: String = "",
        val inReplyTo: String? = null,
        /** Set when reopening a saved draft; saving again updates that draft. */
        val draftId: String? = null,
        val cc: String = "",
        val bcc: String = "",
        /** Own address (primary or alias) to send from; null = primary. */
        val from: String? = null,
    ) : Route

    /** Immutable value describing reader; carries messageId. */
    @Serializable
    data class Reader(val messageId: String) : Route

    /** Everything outgoing: Drafts, Outbox (queued sends) and Sent. */
    @Serializable
    data object Out : Route

    /** Singleton holding settings state and operations. */
    @Serializable
    data object Settings : Route
}
