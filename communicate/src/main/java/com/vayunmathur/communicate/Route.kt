package com.vayunmathur.communicate

import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/** Navigation routes for the communicate app (type-safe back stack keys). */
sealed interface Route : NavKey {
    /** The conversation list tab (home). Sibling of [Dialer] and [CallLogs]. */
    @Serializable data object Messages : Route
    /** The dialer tab. Sibling of [Messages] and [CallLogs]. */
    @Serializable data object Dialer : Route
    /** The call-logs tab. Sibling of [Messages] and [Dialer]. */
    @Serializable data object CallLogs : Route
    /** The accounts / line management route. */
    @Serializable data object Accounts : Route
    /** The Google Voice sign-in route. */
    @Serializable data object GoogleVoiceSignIn : Route
    /**
     * The WhatsApp phone-number registration wizard: one sub-destination
     * per step, pushed in flow order. [Pin] is conditional (only numbers
     * with two-step verification reach it), and reaching [Done] replaces
     * the whole sub-stack so leaving Done exits registration. The entered
     * country code and number travel in the step arguments.
     */
    @Serializable
    sealed interface WhatsAppRegistration : Route {
        /** Enter the number and request an OTP. */
        @Serializable data object Number : WhatsAppRegistration
        /** Enter the OTP for [number]. */
        @Serializable data class Code(val cc: String, val number: String) : WhatsAppRegistration
        /** Enter the two-step PIN for [number] (conditional on the Code result). */
        @Serializable data class Pin(val cc: String, val number: String) : WhatsAppRegistration
        /** Registration succeeded for [number]. */
        @Serializable data class Done(val cc: String, val number: String) : WhatsAppRegistration
    }
    /** The WhatsApp backup import route. */
    @Serializable data object WhatsAppBackupImport : Route
    /**
     * The Signal phone-number registration wizard: one sub-destination
     * per step, pushed in flow order. [Captcha] is conditional (only
     * challenged numbers reach it, carrying the transport to resume),
     * and reaching [Done] replaces the whole sub-stack so leaving Done
     * exits registration. The entered country code and number travel in
     * the step arguments.
     */
    @Serializable
    sealed interface SignalRegistration : Route {
        /** Enter the number and request an OTP. */
        @Serializable data object Number : SignalRegistration
        /** Solve the captcha for [number] and resume requesting via [transport]. */
        @Serializable data class Captcha(
            val cc: String,
            val number: String,
            val transport: String,
        ) : SignalRegistration
        /** Enter the OTP for [number]. */
        @Serializable data class Code(val cc: String, val number: String) : SignalRegistration
        /** Registration succeeded for [number]. */
        @Serializable data class Done(val cc: String, val number: String) : SignalRegistration
    }

    /** The RCS status / provisioning route. */
    @Serializable data object RcsStatus : Route

    /** The RCS safety-number verification route for one peer. */
    @Serializable
    data class RcsVerify(val peerE164: String) : Route

    /** The RCS group member list route. */
    @Serializable
    data class RcsGroupMembers(val conversationId: String) : Route

    /**
     * The full-screen in-app call. Pushed by the call-route sync while
     * [InAppCallRegistry] reports a phase other than Idle, and popped when
     * the call ends: the registry is the session engine, this destination
     * is only its screen. It carries no arguments because the live call
     * state (peer, capabilities, media) changes under it and is read from
     * the registry inside the call screen.
     */
    @Serializable data object Call : Route

    /** One open conversation: its thread, peer address and line. */
    @Serializable
    data class Conversation(
        val threadId: Long,
        val address: String,
        // Serialized as the enum name; defaults keep older back-stack entries valid.
        val line: CommunicateLine = CommunicateLine.Sim,
        val remoteId: String? = null,
        val subscriptionId: Int? = null,
        val isGroup: Boolean = false,
        val participants: List<String> = emptyList(),
        val groupTitle: String? = null,
        /** Shared text seeding the composer (Direct Share / picker share); null for a plain open. */
        val sharedText: String? = null,
        /** Shared stream URIs seeding the composer attachments, as strings. */
        val sharedAttachmentUris: List<String> = emptyList(),
    ) : Route
}
