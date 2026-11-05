import Foundation

/// Peer key lookup over the live relay's binary socket — the same GETKEY exchange the
/// Android client uses, so both platforms read a peer's key capability through one code
/// path and one set of status codes:
///
///     client→server  [0x04][u64 target]
///     server→client  [0x05][status][u64 target][bundle…]
///
/// status: 0 = not registered, 1 = classic key only (outdated app), 2 = PQC bundle follows.
///
/// One request/response per connection, and deliberately **no SUB frame**. SUB is what
/// makes the relay treat a socket as a live subscriber and fan that user's locations out
/// to it instead of the queue that `/api/location/receive_pqc` drains — which this app
/// still polls. Without SUB the socket is inert apart from this exchange, so the HTTP
/// receive path is untouched.
///
/// Replaces the two-request HTTP probe (`/api/pqc/getkey` then `/api/getkey`): one frame
/// returns the status *and* the bundle together, so there is no window between deciding a
/// peer has a PQC key and fetching the key that gets verified.
enum RelayKeyLookup {
    /// Key capability of a peer, as reported by the relay.
    enum Status: UInt8 {
        case noKey = 0
        case classic = 1
        case pqc = 2
    }

    struct KeyResult {
        let status: Status
        /// The peer's public bundle — present only for `.pqc`.
        let bundle: Data?
    }

    private static let url = URL(string: "wss://findfamily.cc/api/ws")!

    /// Matches the Android client's GETKEY timeout.
    private static let timeout: TimeInterval = 5

    private static let opGetKeyRequest: UInt8 = 0x04
    private static let opGetKeyResponse: UInt8 = 0x05

    /// Looks up `userId`'s key. Returns nil when the socket can't be established or the
    /// reply doesn't arrive in time; callers treat that as "unknown" rather than "absent".
    static func getKey(userId: Int64) async -> KeyResult? {
        let session = URLSession(configuration: .ephemeral)
        let task = session.webSocketTask(with: url)
        task.resume()
        defer {
            task.cancel(with: .goingAway, reason: nil)
            session.invalidateAndCancel()
        }

        var request = Data([opGetKeyRequest])
        withUnsafeBytes(of: UInt64(bitPattern: userId).bigEndian) { request.append(contentsOf: $0) }
        guard (try? await task.send(.data(request))) != nil else { return nil }

        return await firstKeyResponse(task, target: userId)
    }

    /// Reads frames until the KEYRESP for `target` arrives, or the timeout wins. The relay
    /// only ever answers a GETKEY on an unsubscribed socket, but skipping non-matching
    /// frames keeps this from being desynced by anything else it might send.
    private static func firstKeyResponse(_ task: URLSessionWebSocketTask, target: Int64) async -> KeyResult? {
        await withTaskGroup(of: KeyResult?.self) { group in
            group.addTask {
                while true {
                    guard let message = try? await task.receive() else { return nil }
                    guard case .data(let frame) = message else { continue }
                    if let result = parse(frame, target: target) { return result }
                }
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

    /// Parses `[0x05][status][u64 target][bundle…]`, or nil if this isn't the KEYRESP for
    /// `target`.
    private static func parse(_ frame: Data, target: Int64) -> KeyResult? {
        let bytes = [UInt8](frame)
        guard bytes.count >= 10, bytes[0] == opGetKeyResponse,
              let status = Status(rawValue: bytes[1]) else { return nil }
        var echoed: UInt64 = 0
        for i in 0..<8 { echoed = (echoed << 8) | UInt64(bytes[2 + i]) }
        guard Int64(bitPattern: echoed) == target else { return nil }
        let bundle = bytes.count > 10 ? Data(bytes[10...]) : nil
        return KeyResult(status: status, bundle: bundle)
    }
}
