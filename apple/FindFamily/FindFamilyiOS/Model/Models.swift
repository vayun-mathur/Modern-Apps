import Foundation
import CoreLocation

// MARK: - Coord

struct Coord: Codable, Hashable, Equatable {
    var lat: Double
    var lon: Double

    init(_ lat: Double, _ lon: Double) {
        self.lat = lat
        self.lon = lon
    }
    init(lat: Double, lon: Double) {
        self.lat = lat
        self.lon = lon
    }

    var clLocationCoordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    static let zero = Coord(0, 0)
}

/// Great-circle distance in meters (Haversine).
func haversine(_ p1: Coord, _ p2: Coord) -> Double {
    let R = 6_371_000.0
    let dLat = (p2.lat - p1.lat) * .pi / 180
    let dLon = (p2.lon - p1.lon) * .pi / 180
    let a = sin(dLat / 2) * sin(dLat / 2)
        + cos(p1.lat * .pi / 180) * cos(p2.lat * .pi / 180) * sin(dLon / 2) * sin(dLon / 2)
    let c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return R * c
}

// MARK: - Request status

enum RequestStatus: String, Codable, CaseIterable {
    case mutualConnection = "MUTUAL_CONNECTION"
    case awaitingRequest = "AWAITING_REQUEST"
    case awaitingResponse = "AWAITING_RESPONSE"
}

// MARK: - User

struct User: Identifiable, Codable, Hashable, Equatable {
    var id: Int64
    var name: String
    /// Either an "image-data:base64,..." string, a contact identifier, or nil.
    var photo: String?
    var locationName: String
    var sendingEnabled: Bool
    var requestStatus: RequestStatus
    var lastLocationChangeTime: Date
    /// Cached recipient public key (base64-encoded PEM).
    var encryptionKey: String?
    /// Peer device platform (`"android"` or `"ios"`), learned from incoming heartbeats.
    /// Used by the UWB ranging path to pick same-platform vs cross-platform protocol.
    var platform: String? = nil
    /// Post-quantum public bundle (base64 of [4B kemLen][kemPubDer][dsaPubDer]), nullable for migration.
    /// Same format as Android Office `Pqc.bundle` / `ff_pqc` key directory.
    var pqcEncryptionKey: String? = nil
    /// Id of the waypoint this user is currently inside, used for enter/exit hysteresis.
    /// Nullable for migration and when the user is outside every waypoint.
    var lastWaypointId: Int64? = nil
    /// Deadline at which `sendingEnabled` auto-flips (the "Disable/Enable after" timer).
    /// `nil` means "Never" (no pending auto-toggle). Nullable for migration.
    var sharingAutoToggleAt: Date? = nil

    static let empty = User(
        id: 0, name: "", photo: nil, locationName: "Unnamed Location",
        sendingEnabled: false, requestStatus: .mutualConnection,
        lastLocationChangeTime: Date(), encryptionKey: nil, platform: nil, pqcEncryptionKey: nil,
        lastWaypointId: nil, sharingAutoToggleAt: nil
    )
}

// MARK: - Waypoint

struct Waypoint: Identifiable, Codable, Hashable, Equatable {
    var id: Int64
    var name: String
    /// Geofence radius in meters.
    var range: Double
    var coord: Coord

    static let newWaypoint = Waypoint(id: 0, name: "", range: 100, coord: .zero)
}

// MARK: - LocationValue

struct LocationValue: Identifiable, Codable, Hashable, Equatable {
    var id: Int64
    var userid: Int64
    var coord: Coord
    /// Meters per second.
    var speed: Float
    /// Horizontal accuracy in meters.
    var acc: Float
    var timestamp: Date
    /// 0–100.
    var battery: Float

    /// Convert to wire form. ULong on the wire, epoch-millis timestamp.
    func toCompatible(sleep: Bool? = nil, senderPlatform: String? = nil) -> LocationValueCompatible {
        LocationValueCompatible(
            id: UInt64(bitPattern: id),
            userid: UInt64(bitPattern: userid),
            coord: coord,
            speed: speed,
            acc: acc,
            timestamp: Int64((timestamp.timeIntervalSince1970 * 1000).rounded()),
            battery: battery,
            sleep: sleep,
            senderPlatform: senderPlatform
        )
    }
}

/// JSON wire format for /api/location/* (mirrors `LocationValueCompatible` in Modern-Apps).
/// The Modern-Apps Kotlin model has a NESTED `coord` object (the Room `@Embedded` flattens
/// to DB columns, but kotlinx.serialization keeps the field nested in JSON).
///
/// `id` and `sleep` are optional here because kotlinx.serialization's default
/// `encodeDefaults = false` omits fields that equal their declared default value
/// (`id = 0uL`, `sleep = null`), so the inbound JSON may not include them.
struct LocationValueCompatible: Codable {
    var id: UInt64?
    var userid: UInt64
    var coord: Coord
    var speed: Float
    var acc: Float
    var timestamp: Int64  // epoch millis
    var battery: Float
    var sleep: Bool?
    /// Sender's platform tag (`"android"` or `"ios"`). Optional for backward compatibility.
    var senderPlatform: String?

    func toLocationValue() -> LocationValue {
        LocationValue(
            id: Int64(bitPattern: id ?? 0),
            userid: Int64(bitPattern: userid),
            coord: coord,
            speed: speed,
            acc: acc,
            timestamp: Date(timeIntervalSince1970: TimeInterval(timestamp) / 1000.0),
            battery: battery
        )
    }
}

// MARK: - TemporaryLink

struct TemporaryLink: Identifiable, Codable, Hashable, Equatable {
    var id: Int64
    var name: String
    /// Base64(PEM) RSA private key — embedded in the share URL fragment, never sent to the server.
    var key: String
    /// Base64(PEM) RSA public key — registered for the link's id and used by senders.
    var publicKey: String
    var deleteAt: Date
    /// PQC ephemeral public bundle (base64 [4B kemLen][kemPub][dsaPub]), nullable for migration.
    var pqcPublicKey: String? = nil
    /// Legacy: the full PQC private bundle (base64 [4B kemPrivLen][kemPriv][dsaPriv]) that older
    /// builds put in the fragment as `#pqc_key=`. Only set on links created before [pqcSeed].
    var pqcKey: String? = nil
    /// The 32-byte ML-KEM link seed (base64url, unpadded) — what goes in the fragment as `#s=`.
    /// This is the link's entire secret; the recipient's browser expands it into the keypair.
    var pqcSeed: String? = nil
}
