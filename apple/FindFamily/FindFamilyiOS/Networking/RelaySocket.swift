import Foundation
import CryptoKit
import OSLog
import UIKit

private let relayLog = Logger(subsystem: "cc.findfamily.ios.swift", category: "Relay")

/// The one long-lived relay socket (`wss://api.maproj.org/api/ws`), mirroring the Android
/// client (`Networking.kt` + `NetworkingLive.kt`) plus the server's reliable-delivery mode:
///
///     client→server SUB_RELIABLE : [0x0D][u8 version=1][u64 userid][PQC bundle…]
///     server→client SUB_ACK      : [0x10][u8 version][u16 idleTimeoutSecs][u16 inflightMax]
///     server→client MSG_SEQ      : [0x0E][flags][u64 seq][ciphertext…]
///     client→server ACK          : [0x0F][u64 seq]               (cumulative)
///     client→server PUB          : [0x02][flags][u64 recipient][ciphertext…]
///     client→server GETKEY       : [0x04][u64 target]
///     server→client KEYRESP      : [0x05][status][u64 target][bundle…]
///     legacy fallback            : SUB [0x01][u64 userid][bundle…] / MSG [0x03][flags][ciphertext…]
///
/// All integers big-endian; flags bit0 = UWB envelope (else location).
///
/// Reliability contract (server side in location_share_server `findfamily.rs`): every
/// MSG_SEQ stays on the server until we ACK it, and is requeued if the socket dies first.
/// So each frame is ACKed only after it has been decrypted and stored, and a frame can
/// arrive twice (ACK lost in a disconnect); `RelayDedup` drops repeats by ciphertext hash.
///
/// The HTTP `/api/location/receive_pqc` + `/api/uwb/receive_pqc` drain is kept as a safety
/// net: once per wake, and on every heartbeat tick while the socket is not subscribed.
@MainActor
final class RelaySocket {
    static let shared = RelaySocket()

    private static let url = URL(string: "wss://api.maproj.org/api/ws")!

    private static let opSub: UInt8 = 0x01
    private static let opPub: UInt8 = 0x02
    private static let opMsg: UInt8 = 0x03
    private static let opGetKeyRequest: UInt8 = 0x04
    private static let opGetKeyResponse: UInt8 = 0x05
    private static let opSubReliable: UInt8 = 0x0D
    private static let opMsgSeq: UInt8 = 0x0E
    private static let opAck: UInt8 = 0x0F
    private static let opSubAck: UInt8 = 0x10
    private static let reliableVersion: UInt8 = 1
    private static let flagUwb: UInt8 = 0x01

    /// No SUB_ACK within this window means an older server that ignores 0x0D: fall back
    /// to a plain SUB (no ACKs) on the same socket.
    private static let subAckTimeout: TimeInterval = 10
    /// Matches the Android client's GETKEY timeout.
    private static let keyTimeout: TimeInterval = 5
    /// Matches Android's reconnect backoff (1 s → 15 s).
    private static let backoffInitial: TimeInterval = 1
    private static let backoffMax: TimeInterval = 15
    /// Ping cadence until SUB_ACK says otherwise: the server's 75 s idle timeout / 3.
    private static let defaultPingInterval: TimeInterval = 25
    /// A wake this long after the previous one means the app was suspended in between.
    private static let wakeGap: TimeInterval = 30

    private let session: URLSession

    private var loopTask: Task<Void, Never>?
    private var loopID = 0
    private var wantRunning = false

    /// The connected socket, if any.
    private var task: URLSessionWebSocketTask?
    /// True once the server accepted our subscription (SUB_ACK, or the legacy fallback SUB).
    private(set) var isSubscribed = false
    /// True when the server confirmed reliable mode (MSG_SEQ + ACK).
    private var reliable = false
    private var legacyFallbackSent = false
    private var pingInterval: TimeInterval = RelaySocket.defaultPingInterval
    /// Last time anything (frame or pong) arrived on the current socket.
    private var lastInboundAt = Date.distantPast
    /// Keepalive for the current socket; started once subscribed, so it uses the interval
    /// the server advertised in SUB_ACK from the first ping.
    private var pingTask: Task<Void, Never>?

    private var lastWakeAt = Date.distantPast
    private var httpDrainInFlight = false
    private var lifecycleObservers: [NSObjectProtocol] = []

    /// Outstanding GETKEYs on the live socket, keyed by target userid.
    private var pendingKeys: [Int64: AsyncStream<RelayKeyLookup.KeyResult?>.Continuation] = [:]

    private init() {
        let cfg = URLSessionConfiguration.default
        // Surface "offline" as a failed connect (handled by backoff) instead of parking
        // the handshake until connectivity returns.
        cfg.waitsForConnectivity = false
        // Pongs every pingInterval keep traffic flowing well inside this.
        cfg.timeoutIntervalForRequest = 120
        self.session = URLSession(configuration: cfg)
    }

    // MARK: - Lifecycle

    /// Called once after `Networking.start()`: connect now if the app is in the
    /// foreground, connect whenever it becomes active, and close cleanly when it goes to
    /// the background without location tracking (iOS would suspend it anyway; a clean
    /// close lets the server requeue at once instead of after its idle timeout).
    func beginLifecycle() {
        guard lifecycleObservers.isEmpty else { return }
        let center = NotificationCenter.default
        lifecycleObservers.append(center.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in RelaySocket.shared.wake() }
        })
        lifecycleObservers.append(center.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { _ in
            Task { @MainActor in
                if !BackgroundTask.shared.isHeartbeatRunning { RelaySocket.shared.stop() }
            }
        })
        if UIApplication.shared.applicationState != .background {
            wake()
        }
    }

    /// Any sign of life: app became active, tracking started, a location update or
    /// heartbeat tick. Cheap when already connected. Starts the socket if needed, forces
    /// a reconnect if the current one has gone quiet (typical after suspension), and runs
    /// one HTTP drain per wake as a safety net.
    func wake() {
        guard Networking.shared.isReady, Networking.shared.pqcAvailable else { return }
        let now = Date()
        if now.timeIntervalSince(lastWakeAt) > Self.wakeGap {
            drainHttpOnce()
        }
        lastWakeAt = now
        if let task, isSubscribed, now.timeIntervalSince(lastInboundAt) > pingInterval * 2 {
            relayLog.notice("socket quiet for \(Int(now.timeIntervalSince(self.lastInboundAt)))s on wake; reconnecting")
            task.cancel(with: .goingAway, reason: nil)
        }
        start()
    }

    /// Location tracking stopped (not sharing with anyone). Keep the socket unless the app
    /// is in the background, so the map stays live on screen. Checks `.background` rather
    /// than `!= .active`: at a cold start the sharing gate runs while the app is still
    /// `.inactive`, possibly after `didBecomeActive` already fired.
    func trackingStopped() {
        guard UIApplication.shared.applicationState == .background else { return }
        stop()
    }

    func start() {
        guard Networking.shared.isReady, Networking.shared.pqcAvailable else { return }
        wantRunning = true
        guard loopTask == nil else { return }
        loopID += 1
        let id = loopID
        loopTask = Task { [weak self] in
            await self?.runLoop()
            if self?.loopID == id { self?.loopTask = nil }
        }
    }

    /// Closes the socket cleanly (the server then requeues anything we did not ACK).
    func stop() {
        wantRunning = false
        loopID += 1
        loopTask?.cancel()
        loopTask = nil
        if let task {
            task.cancel(with: .normalClosure, reason: nil)
            teardown(task)
        }
    }

    // MARK: - Connection loop

    private func runLoop() async {
        var backoff = Self.backoffInitial
        while wantRunning && !Task.isCancelled {
            let subscribed = await runOneConnection()
            if !wantRunning || Task.isCancelled { break }
            if subscribed { backoff = Self.backoffInitial }
            try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
            backoff = min(backoff * 2, Self.backoffMax)
        }
    }

    /// One socket from connect to death. Returns whether it got subscribed.
    private func runOneConnection() async -> Bool {
        let task = session.webSocketTask(with: Self.url)
        self.task = task
        isSubscribed = false
        reliable = false
        legacyFallbackSent = false
        pingInterval = Self.defaultPingInterval
        lastInboundAt = Date()
        task.resume()

        var everSubscribed = false
        defer { teardown(task) }

        guard (try? await task.send(.data(subscribeFrame(op: Self.opSubReliable)))) != nil else {
            return false
        }
        let fallback = Task { [weak self] in
            guard let self else { return }
            await self.legacyFallbackIfNoSubAck(task)
        }
        defer { fallback.cancel() }

        do {
            while true {
                let message = try await task.receive()
                lastInboundAt = Date()
                guard case .data(let frame) = message else { continue }
                await handle(frame, on: task)
                if isSubscribed { everSubscribed = true }
            }
        } catch {
            relayLog.notice("relay socket ended: \(error.localizedDescription)")
        }
        return everSubscribed
    }

    /// Clears per-socket state, but only if `task` is still the current socket (a stop()
    /// followed by start() may already have replaced it).
    private func teardown(_ task: URLSessionWebSocketTask) {
        guard self.task === task else { return }
        task.cancel(with: .goingAway, reason: nil)
        self.task = nil
        pingTask?.cancel()
        pingTask = nil
        isSubscribed = false
        reliable = false
        for (_, waiter) in pendingKeys { waiter.finish() }
        pendingKeys.removeAll()
    }

    /// Pings every `pingInterval` (the server closes reliable sockets after 3 silent
    /// intervals) and, like Android's supervisor, treats 3 intervals without any inbound
    /// frame or pong as a half-open socket and forces a reconnect.
    private func pingLoop(_ task: URLSessionWebSocketTask) async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: UInt64(pingInterval * 1_000_000_000))
            if Task.isCancelled || self.task !== task { return }
            task.sendPing { error in
                guard error == nil else { return }
                Task { @MainActor in
                    if RelaySocket.shared.task === task { RelaySocket.shared.lastInboundAt = Date() }
                }
            }
            let idle = Date().timeIntervalSince(lastInboundAt)
            if idle > pingInterval * 3 {
                relayLog.notice("no inbound for \(Int(idle))s; socket half-open, reconnecting")
                task.cancel(with: .goingAway, reason: nil)
                return
            }
        }
    }

    private func legacyFallbackIfNoSubAck(_ task: URLSessionWebSocketTask) async {
        try? await Task.sleep(nanoseconds: UInt64(Self.subAckTimeout * 1_000_000_000))
        guard !Task.isCancelled, self.task === task, !isSubscribed else { return }
        relayLog.notice("no SUB_ACK; server predates reliable mode, falling back to plain SUB")
        legacyFallbackSent = true
        guard (try? await task.send(.data(subscribeFrame(op: Self.opSub)))) != nil else { return }
        if self.task === task { markSubscribed(reliable: false, on: task) }
    }

    private func markSubscribed(reliable: Bool, on task: URLSessionWebSocketTask) {
        self.reliable = reliable
        isSubscribed = true
        lastInboundAt = Date()
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            guard let self else { return }
            await self.pingLoop(task)
        }
        relayLog.info("relay subscribed reliable=\(reliable) ping=\(Int(self.pingInterval))s")
    }

    // MARK: - Frames

    /// `[op][(version)][u64 userid][PQC bundle…]` — SUB doubles as registration.
    private func subscribeFrame(op: UInt8) -> Data {
        var frame = Data([op])
        if op == Self.opSubReliable { frame.append(Self.reliableVersion) }
        Self.appendU64(&frame, UInt64(bitPattern: Networking.shared.userid))
        if let bundle = Networking.shared.pqcPublicBundle { frame.append(bundle) }
        return frame
    }

    private func handle(_ frame: Data, on task: URLSessionWebSocketTask) async {
        let bytes = [UInt8](frame)
        guard let op = bytes.first else { return }
        switch op {
        case Self.opSubAck:
            // [0x10][version][u16 idleSecs][u16 inflightMax]
            guard bytes.count >= 6, !legacyFallbackSent else { return }
            let idle = TimeInterval(Self.readU16(bytes, 2))
            if idle > 0 { pingInterval = min(max(idle / 3, 5), 60) }
            markSubscribed(reliable: true, on: task)
        case Self.opMsgSeq:
            // [0x0E][flags][u64 seq][ciphertext…]
            guard bytes.count >= 10 else { return }
            let seq = Self.readU64(bytes, 2)
            await deliver(Data(bytes[10...]), isUwb: bytes[1] & Self.flagUwb != 0)
            // ACK only after the payload is processed and stored: an ACK is the server's
            // licence to forget the frame.
            var ack = Data([Self.opAck])
            Self.appendU64(&ack, seq)
            try? await task.send(.data(ack))
        case Self.opMsg:
            // Legacy fallback: [0x03][flags][ciphertext…], no ACK.
            guard bytes.count >= 2 else { return }
            await deliver(Data(bytes[2...]), isUwb: bytes[1] & Self.flagUwb != 0)
        case Self.opGetKeyResponse:
            guard let reply = RelayKeyLookup.parseAny(frame) else { return }
            pendingKeys.removeValue(forKey: reply.target)?.yield(reply.result)
        default:
            break
        }
    }

    /// Decrypts and hands one pushed payload to the same pipeline the HTTP drain feeds.
    /// A repeat (redelivered after a lost ACK) is skipped but still ACKed by the caller.
    private func deliver(_ sealed: Data, isUwb: Bool) async {
        if RelayDedup.shared.contains(sealed) { return }
        if isUwb {
            if let env = Networking.shared.decodeUwb(sealed: sealed) {
                BackgroundTask.shared.handleIncomingUwb([env])
            }
        } else if let loc = Networking.shared.decodeLocation(sealed: sealed) {
            await BackgroundTask.shared.handleIncomingLocations([loc])
        }
        // Recorded even when decryption failed: a byte-identical retry fails the same way.
        RelayDedup.shared.insert(sealed)
    }

    // MARK: - Requests

    /// Sends an already-sealed payload as PUB. False when there is no fresh subscribed
    /// socket — the caller then publishes over HTTP. PUB has no server acknowledgement, so
    /// a socket that has been quiet longer than one ping interval (+10 s) is not trusted.
    func publish(recipient: Int64, isUwb: Bool, sealed: Data) async -> Bool {
        guard let task, isSubscribed,
              Date().timeIntervalSince(lastInboundAt) < pingInterval + 10 else { return false }
        var frame = Data([Self.opPub, isUwb ? Self.flagUwb : 0])
        Self.appendU64(&frame, UInt64(bitPattern: recipient))
        frame.append(sealed)
        do {
            try await task.send(.data(frame))
            return true
        } catch {
            return false
        }
    }

    /// GETKEY over the live socket. Nil when there is no subscribed socket, another
    /// lookup for the same target is in flight, or no reply within the timeout.
    func getKey(userId: Int64) async -> RelayKeyLookup.KeyResult? {
        guard let task, isSubscribed, pendingKeys[userId] == nil else { return nil }
        let (stream, continuation) = AsyncStream<RelayKeyLookup.KeyResult?>.makeStream()
        pendingKeys[userId] = continuation
        defer {
            pendingKeys.removeValue(forKey: userId)?.finish()
        }
        var request = Data([Self.opGetKeyRequest])
        Self.appendU64(&request, UInt64(bitPattern: userId))
        guard (try? await task.send(.data(request))) != nil else { return nil }
        let timeout = Self.keyTimeout
        return await withTaskGroup(of: RelayKeyLookup.KeyResult?.self) { group in
            group.addTask {
                for await result in stream { return result }
                return nil
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    // MARK: - HTTP safety net

    /// One HTTP drain of both PQC queues, fed into the same pipeline as socket pushes.
    private func drainHttpOnce() {
        guard !httpDrainInFlight else { return }
        httpDrainInFlight = true
        Task {
            if let locations = await Networking.shared.receiveLocations(), !locations.isEmpty {
                await BackgroundTask.shared.handleIncomingLocations(locations)
            }
            if let envelopes = await Networking.shared.receiveUwbMessages(), !envelopes.isEmpty {
                BackgroundTask.shared.handleIncomingUwb(envelopes)
            }
            self.httpDrainInFlight = false
        }
    }

    // MARK: - Byte helpers

    private static func appendU64(_ data: inout Data, _ value: UInt64) {
        withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
    }

    private static func readU64(_ bytes: [UInt8], _ offset: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<8 { value = (value << 8) | UInt64(bytes[offset + i]) }
        return value
    }

    private static func readU16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        (UInt16(bytes[offset]) << 8) | UInt16(bytes[offset + 1])
    }
}

/// Recently processed relay payloads, by ciphertext hash, so a frame redelivered after a
/// lost ACK (or also drained over HTTP) is not stored or notified twice. Exact: every PQC
/// seal has a fresh ML-KEM encapsulation and random IV, so equal bytes == same message.
/// Persisted so a kill between "stored" and "ACKed" does not duplicate on relaunch.
@MainActor
final class RelayDedup {
    static let shared = RelayDedup()

    private static let defaultsKey = "relaySeenPayloads_v1"
    private static let capacity = 512

    private var order: [String]
    private var seen: Set<String>

    private init() {
        order = UserDefaults.standard.stringArray(forKey: Self.defaultsKey) ?? []
        seen = Set(order)
    }

    private static func digest(_ payload: Data) -> String {
        Data(Array(SHA256.hash(data: payload)).prefix(16)).base64EncodedString()
    }

    func contains(_ payload: Data) -> Bool {
        seen.contains(Self.digest(payload))
    }

    func insert(_ payload: Data) {
        let d = Self.digest(payload)
        guard seen.insert(d).inserted else { return }
        order.append(d)
        if order.count > Self.capacity {
            let overflow = order.count - Self.capacity
            for old in order.prefix(overflow) { seen.remove(old) }
            order.removeFirst(overflow)
        }
        UserDefaults.standard.set(order, forKey: Self.defaultsKey)
    }
}
