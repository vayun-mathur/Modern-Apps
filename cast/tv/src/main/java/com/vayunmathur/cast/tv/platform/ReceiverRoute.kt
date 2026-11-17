package com.vayunmathur.cast.tv.platform

import com.vayunmathur.cast.tv.Route

/**
 * The phase-to-destination projection.
 *
 * [ReceiverPhase.Mirroring] has no destination of its own: the picture
 * lives in `MirrorActivity`, launched by `MainActivity` the moment frames
 * arrive. Underneath it the stack holds [Route.Connected] naming the
 * source, which is also the held-breath screen seen in the moment between
 * the stream starting and that Activity coming up.
 */
fun receiverRouteFor(phase: ReceiverPhase): Route = when (phase) {
    ReceiverPhase.Starting -> Route.Starting
    ReceiverPhase.Advertising -> Route.Ready
    is ReceiverPhase.Pairing -> Route.Pairing(
        senderName = phase.senderName,
        code = phase.code,
        attemptsLeft = phase.attemptsLeft,
        codeChanged = phase.codeChanged,
    )
    is ReceiverPhase.Connected -> Route.Connected(phase.senderName)
    is ReceiverPhase.Mirroring -> Route.Connected(phase.sourceName)
    is ReceiverPhase.Failed -> Route.Failed(phase.reason)
}
