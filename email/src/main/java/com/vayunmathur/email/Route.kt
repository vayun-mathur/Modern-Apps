package com.vayunmathur.email

import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

@Serializable
/** Navigation destinations for the email app. */
sealed interface Route : NavKey {
    @Serializable
    /** Destination showing the unified message list. */
    object MessageList : Route
    @Serializable
    /** Destination showing one message thread for an account. */
    data class MessageThread(val accountEmail: String, val threadId: String) : Route
    @Serializable
    /** Destination for composing or editing a message or draft. */
    data class Composer(
        val to: String = "",
        val subject: String = "",
        val body: String = "",
        val inReplyTo: String? = null,
        val references: String? = null,
        val draftId: Long? = null
    ) : Route
    @Serializable
    /** Destination showing queued outgoing messages. */
    object Outbox : Route
    @Serializable
    /** Destination showing saved drafts. */
    object Drafts : Route
    @Serializable
    /** Destination for adding a new mail account (the provider picker). */
    object AddAccount : Route
    @Serializable
    /** Destination for the account form of the provider picked on [AddAccount]. */
    data class AddAccountForm(val providerId: String) : Route
    @Serializable
    /** Destination showing email settings. */
    object Settings : Route
    @Serializable
    /** Destination viewing an opened .eml file identified by its URI string. */
    data class EmlViewer(val uriString: String) : Route
}
