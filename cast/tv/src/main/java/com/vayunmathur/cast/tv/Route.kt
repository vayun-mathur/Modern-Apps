package com.vayunmathur.cast.tv

import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * The receiver's screens as typed destinations.
 *
 * `ReceiverPhase` (in platform/) stays the session engine's state:
 * `ReceiverController` and its media loops write phases as sockets and
 * decoders move, and this graph is only their projection onto the screen
 * (see `receiverRouteFor` in platform/ReceiverRoute.kt). The stack is
 * always one deep (see Navigation.kt) — there is no back behaviour between
 * receiver states, nobody navigates a TV back from Paired to Pairing — but
 * typed destinations buy arguments that cannot drift from the phase that
 * produced them, and a stack that restores the right screen on recreation
 * instead of restarting from [Starting].
 */
@Serializable
sealed interface Route : NavKey {
    /** Loading the device identity and binding sockets. */
    @Serializable
    data object Starting : Route

    /** Announced on the LAN, waiting for a phone. */
    @Serializable
    data object Ready : Route

    /** A phone we do not know is connecting; [code] is what the user types. */
    @Serializable
    data class Pairing(
        val senderName: String,
        val code: String,
        val attemptsLeft: Int,
        val codeChanged: Boolean = false,
    ) : Route

    /** Paired; the stream is being set up. */
    @Serializable
    data class Connected(val senderName: String) : Route

    /** Something the user has to know about. */
    @Serializable
    data class Failed(val reason: ReceiverFailure) : Route
}

