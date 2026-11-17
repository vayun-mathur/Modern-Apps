package com.vayunmathur.findfamily.domain

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-global UWB envelope inbox.
 *
 * Producer: [com.vayunmathur.findfamily.platform.LocationTrackingService] drains
 *   `/api/uwb/receive` once per heartbeat tick and [emit]s each envelope here.
 *
 * Consumer: [com.vayunmathur.findfamily.platform.FindFamilyViewModel] subscribes
 *   while the Find Nearby (UWB) screen is open to drive the WaitingForPeer
 *   handshake. When the VM isn't alive (e.g. peer wakes a backgrounded app),
 *   the service itself posts a notification offering to open the screen.
 *
 * SharedFlow with replay = 0 + a small buffer: envelopes are one-shot; missed
 * envelopes (e.g. screen closed before they arrived) are dropped on the floor
 * intentionally — EXCEPT incoming `REQUEST` envelopes, which we cache in
 * [pendingRequests] so a notification-launched screen can find them.
 */
object UwbInbox {
    private val _flow = MutableSharedFlow<UwbEnvelope>(extraBufferCapacity = 16)
    val flow: SharedFlow<UwbEnvelope> = _flow.asSharedFlow()

    /** Most-recent REQUEST envelope from each peer, keyed by sender userid (signed long). */
    private val pendingRequests = ConcurrentHashMap<Long, UwbEnvelope>()

    suspend fun emit(envelope: UwbEnvelope) {
        cacheIfRequest(envelope)
        _flow.emit(envelope)
    }

    /** Non-suspending emit used from non-coroutine call sites. Returns false on overflow. */
    fun tryEmit(envelope: UwbEnvelope): Boolean {
        cacheIfRequest(envelope)
        return _flow.tryEmit(envelope)
    }

    /** Retrieve and remove the most recent pending REQUEST from [peerUserId], if any. */
    fun consumePendingRequest(peerUserId: Long): UwbEnvelope? =
        pendingRequests.remove(peerUserId)

    private fun cacheIfRequest(envelope: UwbEnvelope) {
        if (envelope.kind == UwbEnvelopeKind.REQUEST) {
            pendingRequests[envelope.sender.toLong()] = envelope
        } else if (envelope.kind == UwbEnvelopeKind.CANCEL) {
            // Cancel from peer invalidates any pending request from them.
            pendingRequests.remove(envelope.sender.toLong())
        }
    }
}
