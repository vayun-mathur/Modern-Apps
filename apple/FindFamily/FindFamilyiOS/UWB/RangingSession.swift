import Foundation
import Combine
import NearbyInteraction
import simd
import UIKit

/// SwiftUI-friendly wrapper around `NISession`.
///
/// Owns a single ranging session at a time:
///   - `start(peer:)` performs the platform-appropriate handshake (currently
///     iOS↔iOS only; cross-platform comes in Phase 5) and begins ranging.
///   - `invalidate()` tears down the session and sends a `cancel` envelope so
///     the peer's UI exits too.
///
/// `@MainActor` because all SwiftUI bindings are read on the main thread and
/// `NISession` callbacks are scheduled on whatever queue is set on the delegate
/// (we set `.main`).
@MainActor
final class RangingSession: NSObject, ObservableObject, NISessionDelegate {

    enum Phase: Equatable {
        case idle
        case starting
        case waitingForPeer
        case ranging
        case peerLost
        case unsupported(String)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle
    /// Live distance in meters (nil when not yet measured or peer is out of range).
    @Published private(set) var distance: Float?
    /// Unit vector pointing toward the peer in the device's reference frame
    /// (nil when direction info isn't available — happens when peer is outside
    /// the device's narrow forward field-of-view).
    @Published private(set) var direction: simd_float3?

    private var session: NISession?
    private var peer: User?
    private var currentSessionId: String?
    private var inboxSub: AnyCancellable?
    private var timeoutTask: Task<Void, Never>?
    private let pingHaptic = UIImpactFeedbackGenerator(style: .light)
    private var lastHapticBucket: Int = -1

    deinit {
        // Synchronous deinit; the session is already invalidated by SwiftUI's
        // .onDisappear normally — this is a defensive cleanup.
        session?.invalidate()
    }

    func start(peer: User) {
        guard phase == .idle else { return }
        self.peer = peer
        self.currentSessionId = UUID().uuidString
        self.phase = .starting

        // Hardware capability check.
        guard NISession.deviceCapabilities.supportsPreciseDistanceMeasurement else {
            phase = .unsupported("This device does not support Ultra-Wideband ranging.")
            return
        }

        let s = NISession()
        s.delegate = self
        s.delegateQueue = .main
        self.session = s

        // Cross-platform (peer.platform == "android"): iOS acts as the
        // controller via NINearbyAccessoryConfiguration. The Android peer is
        // the "accessory" and will respond with an accessory-data blob on a
        // separate `config` envelope (see UwbAccessoryProtocol.swift).
        if peer.platform == "android" {
            beginCrossPlatformAsController(peer: peer)
            return
        }

        // Subscribe to incoming envelopes so we hear the peer's reply.
        inboxSub = UwbInbox.shared.publisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] envelope in
                self?.handleIncoming(envelope)
            }

        // Send our discovery token to the peer.
        guard let myToken = s.discoveryToken else {
            phase = .failed("Could not generate UWB token.")
            return
        }
        guard let archived = try? NSKeyedArchiver.archivedData(
            withRootObject: myToken, requiringSecureCoding: true
        ) else {
            phase = .failed("Could not encode UWB token.")
            return
        }

        let envelope = UwbEnvelope(
            sessionId: currentSessionId!,
            sender: UInt64(bitPattern: Networking.shared.userid),
            senderPlatform: Networking.platformTag,
            kind: UwbEnvelopeKind.request,
            payload: UwbHandshake(discoveryTokenB64: archived.base64EncodedString())
        )
        phase = .waitingForPeer
        scheduleTimeout()
        Task { [weak self] in
            guard let self = self else { return }
            let ok = await Networking.shared.publishUwbMessage(envelope, to: peer.id)
            if !ok {
                self.phase = .failed("Could not reach peer.")
                self.invalidateLocal()
            }
        }
    }

    /// Responder-side entry. Called when the screen is opened via a notification tap.
    func accept(request: UwbEnvelope, peer: User) {
        guard phase == .idle else { return }
        self.peer = peer
        self.currentSessionId = request.sessionId
        self.phase = .starting

        guard NISession.deviceCapabilities.supportsPreciseDistanceMeasurement else {
            phase = .unsupported("This device does not support Ultra-Wideband ranging.")
            return
        }

        // Cross-platform: Android initiator pre-packaged its accessoryData in
        // the REQUEST payload. Run NINearbyAccessoryConfiguration directly.
        if request.senderPlatform == "android",
           let payload = request.payload,
           let accessoryDataB64 = payload.accessoryConfigDataB64,
           let accessoryData = Data(base64Encoded: accessoryDataB64) {
            let s = NISession()
            s.delegate = self
            s.delegateQueue = .main
            self.session = s
            inboxSub = UwbInbox.shared.publisher
                .receive(on: DispatchQueue.main)
                .sink { [weak self] envelope in self?.handleIncoming(envelope) }
            do {
                let config = try NINearbyAccessoryConfiguration(
                    accessoryData: accessoryData,
                    bluetoothPeerIdentifier: UUID()
                )
                s.run(config)
                phase = .ranging
            } catch {
                phase = .failed("Could not start accessory session: \(error.localizedDescription)")
                invalidateLocal()
            }
            return
        }

        // Same-platform iOS↔iOS: peer's NIDiscoveryToken is in the payload.
        guard let payload = request.payload,
              let tokenB64 = payload.discoveryTokenB64,
              let tokenData = Data(base64Encoded: tokenB64),
              let peerToken = try? NSKeyedUnarchiver.unarchivedObject(
                ofClass: NIDiscoveryToken.self, from: tokenData
              ) else {
            phase = .failed("Invalid peer token.")
            return
        }

        let s = NISession()
        s.delegate = self
        s.delegateQueue = .main
        self.session = s

        // Send our discovery token back as the ack.
        guard let myToken = s.discoveryToken,
              let archived = try? NSKeyedArchiver.archivedData(
                withRootObject: myToken, requiringSecureCoding: true
              ) else {
            phase = .failed("Could not encode local UWB token.")
            return
        }
        let ack = UwbEnvelope(
            sessionId: request.sessionId,
            sender: UInt64(bitPattern: Networking.shared.userid),
            senderPlatform: Networking.platformTag,
            kind: UwbEnvelopeKind.ack,
            payload: UwbHandshake(discoveryTokenB64: archived.base64EncodedString())
        )
        Task { [weak self] in
            guard let self = self else { return }
            let ok = await Networking.shared.publishUwbMessage(ack, to: peer.id)
            if !ok {
                self.phase = .failed("Could not reach peer.")
                self.invalidateLocal()
                return
            }
            // Start ranging immediately with the peer's token from the request.
            let config = NINearbyPeerConfiguration(peerToken: peerToken)
            s.run(config)
            self.phase = .ranging
        }
    }

    func invalidate() {
        // Notify the peer so their UI can exit too.
        if let sid = currentSessionId, let p = peer {
            let env = UwbEnvelope(
                sessionId: sid,
                sender: UInt64(bitPattern: Networking.shared.userid),
                senderPlatform: Networking.platformTag,
                kind: UwbEnvelopeKind.cancel,
                payload: nil
            )
            Task { await Networking.shared.publishUwbMessage(env, to: p.id) }
        }
        invalidateLocal()
    }

    private func invalidateLocal() {
        timeoutTask?.cancel(); timeoutTask = nil
        inboxSub?.cancel(); inboxSub = nil
        session?.invalidate(); session = nil
        currentSessionId = nil
        peer = nil
        distance = nil
        direction = nil
        phase = .idle
        lastHapticBucket = -1
    }

    private func scheduleTimeout() {
        timeoutTask?.cancel()
        timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            guard let self = self else { return }
            if self.phase == .waitingForPeer {
                self.phase = .failed("Peer did not respond.")
                self.invalidateLocal()
            }
        }
    }

    private func handleIncoming(_ envelope: UwbEnvelope) {
        guard envelope.sessionId == currentSessionId else { return }
        switch envelope.kind {
        case UwbEnvelopeKind.ack:
            startRangingWithAck(envelope)
        case UwbEnvelopeKind.config:
            handleCrossPlatformConfig(envelope)
        case UwbEnvelopeKind.cancel:
            invalidateLocal()
        default:
            break
        }
    }

    // MARK: - Cross-platform (Android ↔ iOS) accessory-protocol path
    //
    // Apple's "UWB Specification for Third-Party Accessories" defines a small
    // back-and-forth: the accessory (Android) sends a binary capability blob
    // ("accessory configuration data"); iOS feeds it into
    // `NINearbyAccessoryConfiguration(accessoryData:)`, runs the session, and
    // receives a "shareableConfigurationData" blob via the delegate, which we
    // return to the accessory so it can configure its own UWB. Transport is
    // the same encrypted /api/uwb/* channel as same-platform.

    private func beginCrossPlatformAsController(peer: User) {
        inboxSub = UwbInbox.shared.publisher
            .receive(on: DispatchQueue.main)
            .sink { [weak self] envelope in self?.handleIncoming(envelope) }

        // Send REQUEST with no payload. Android responds with a CONFIG envelope
        // carrying its accessoryConfigDataB64.
        let envelope = UwbEnvelope(
            sessionId: currentSessionId!,
            sender: UInt64(bitPattern: Networking.shared.userid),
            senderPlatform: Networking.platformTag,
            kind: UwbEnvelopeKind.request,
            payload: nil
        )
        phase = .waitingForPeer
        scheduleTimeout()
        Task { [weak self] in
            guard let self = self else { return }
            let ok = await Networking.shared.publishUwbMessage(envelope, to: peer.id)
            if !ok {
                self.phase = .failed("Could not reach peer.")
                self.invalidateLocal()
            }
        }
    }

    private func handleCrossPlatformConfig(_ envelope: UwbEnvelope) {
        guard let s = session else { return }
        guard let payload = envelope.payload,
              let accessoryDataB64 = payload.accessoryConfigDataB64,
              let accessoryData = Data(base64Encoded: accessoryDataB64) else {
            phase = .failed("Invalid accessory data from peer.")
            invalidateLocal()
            return
        }
        timeoutTask?.cancel(); timeoutTask = nil
        do {
            // NINearbyAccessoryConfiguration parses Apple's standard accessory-data format.
            // bluetoothPeerIdentifier is required by Apple's API since it was designed
            // around a paired BLE peripheral. We pass a dummy UUID because our handshake
            // travels over the existing server channel instead of BLE. NISession may still
            // attempt to use BLE for parts of the protocol; if so, a true cross-platform
            // implementation would need the peer's actual CB peripheral identifier.
            let config = try NINearbyAccessoryConfiguration(
                accessoryData: accessoryData,
                bluetoothPeerIdentifier: UUID()
            )
            s.run(config)
            phase = .ranging
            // The framework will call `session(_:didGenerateShareableConfigurationData:for:)`
            // shortly afterward so we can ship the resulting blob to the accessory.
        } catch {
            phase = .failed("Could not start accessory session: \(error.localizedDescription)")
            invalidateLocal()
        }
    }

    nonisolated func session(
        _ session: NISession,
        didGenerateShareableConfigurationData shareableConfigurationData: Data,
        for object: NINearbyObject
    ) {
        let b64 = shareableConfigurationData.base64EncodedString()
        Task { @MainActor in
            guard let sid = self.currentSessionId, let peer = self.peer else { return }
            let env = UwbEnvelope(
                sessionId: sid,
                sender: UInt64(bitPattern: Networking.shared.userid),
                senderPlatform: Networking.platformTag,
                kind: UwbEnvelopeKind.config,
                payload: UwbHandshake(shareableConfigDataB64: b64)
            )
            _ = await Networking.shared.publishUwbMessage(env, to: peer.id)
        }
    }

    private func startRangingWithAck(_ envelope: UwbEnvelope) {
        guard let s = session else { return }
        guard let payload = envelope.payload,
              let tokenB64 = payload.discoveryTokenB64,
              let tokenData = Data(base64Encoded: tokenB64),
              let peerToken = try? NSKeyedUnarchiver.unarchivedObject(
                ofClass: NIDiscoveryToken.self, from: tokenData
              ) else {
            phase = .failed("Invalid peer ack.")
            invalidateLocal()
            return
        }
        timeoutTask?.cancel(); timeoutTask = nil
        let config = NINearbyPeerConfiguration(peerToken: peerToken)
        s.run(config)
        phase = .ranging
    }

    /// Light haptic ping every time the distance crosses into a smaller meter
    /// bucket (≤5 m), and a stronger pulse below 0.5 m.
    private func maybeHaptic() {
        guard let d = distance else { return }
        let bucket: Int
        if d < 0.5 { bucket = 0 }
        else if d < 1 { bucket = 1 }
        else if d < 5 { bucket = Int(d) + 1 } // 2,3,4,5
        else { bucket = 99 }
        if bucket < lastHapticBucket {
            pingHaptic.impactOccurred(intensity: d < 0.5 ? 1.0 : 0.4)
        }
        lastHapticBucket = bucket
    }

    // MARK: - NISessionDelegate

    nonisolated func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject]) {
        Task { @MainActor in
            guard let obj = nearbyObjects.first else { return }
            self.distance = obj.distance
            self.direction = obj.direction
            self.maybeHaptic()
        }
    }

    nonisolated func session(
        _ session: NISession,
        didRemove nearbyObjects: [NINearbyObject],
        reason: NINearbyObject.RemovalReason
    ) {
        Task { @MainActor in
            self.distance = nil
            self.direction = nil
            self.phase = .peerLost
        }
    }

    nonisolated func sessionWasSuspended(_ session: NISession) {}
    nonisolated func sessionSuspensionEnded(_ session: NISession) {}

    nonisolated func session(_ session: NISession, didInvalidateWith error: Error) {
        Task { @MainActor in
            if let niError = error as? NIError {
                switch niError.code {
                case .userDidNotAllow:
                    self.phase = .unsupported("Precision Finding permission was denied.")
                case .invalidConfiguration:
                    self.phase = .failed("Invalid ranging configuration.")
                default:
                    self.phase = .failed(niError.localizedDescription)
                }
            } else {
                self.phase = .failed(error.localizedDescription)
            }
        }
    }
}
