import Foundation
import Combine

/// Process-global UWB envelope inbox.
///
/// Producer: [BackgroundTask] drains `/api/uwb/receive` once per heartbeat
///   tick (every ~3 s when foregrounded) and forwards each envelope here.
///
/// Consumer: [RangingSession] subscribes while the Precision Finding screen
///   is open to drive the WaitingForPeer handshake. When the view isn't alive
///   (e.g. peer wakes a backgrounded app), the [BackgroundTask] itself posts
///   a notification offering to open the screen.
///
/// PassthroughSubject — envelopes are one-shot. Missed envelopes (e.g. screen
/// closed before they arrived) are dropped on the floor intentionally —
/// EXCEPT incoming REQUEST envelopes, which we cache in [pendingRequests] so
/// a notification-launched screen can find them.
final class UwbInbox: @unchecked Sendable {
    static let shared = UwbInbox()
    private init() {}

    let publisher = PassthroughSubject<UwbEnvelope, Never>()

    private let queue = DispatchQueue(label: "cc.findfamily.uwb.inbox")
    private var pendingRequests: [Int64: UwbEnvelope] = [:]

    func emit(_ envelope: UwbEnvelope) {
        cacheIfRequest(envelope)
        publisher.send(envelope)
    }

    /// Retrieve and remove the most recent pending REQUEST from [peerUserId], if any.
    func consumePendingRequest(from peerUserId: Int64) -> UwbEnvelope? {
        queue.sync {
            pendingRequests.removeValue(forKey: peerUserId)
        }
    }

    private func cacheIfRequest(_ envelope: UwbEnvelope) {
        let senderId = Int64(bitPattern: envelope.sender)
        queue.sync {
            if envelope.kind == UwbEnvelopeKind.request {
                pendingRequests[senderId] = envelope
            } else if envelope.kind == UwbEnvelopeKind.cancel {
                pendingRequests.removeValue(forKey: senderId)
            }
        }
    }
}
