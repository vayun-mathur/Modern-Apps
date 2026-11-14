package com.vayunmathur.findfamily.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

@Serializable
/** Connection state between this device and a person. */
enum class RequestStatus {
    MUTUAL_CONNECTION,
    AWAITING_REQUEST,
    AWAITING_RESPONSE
}

/**
 * Distinguishes a normal person contact from a custom UWB item-tracker. Trackers
 * reuse the [User] row (and therefore the whole map/list/relay pipeline) but are
 * non-interactive: they never publish, never auto-toggle, and only ever appear as
 * a received location pin. Stored by Room as the enum name (`"PERSON"`/`"TRACKER"`).
 * Gated behind `BuildConfig.DEV_BUILD`; release rows are always [PERSON].
 */
@Serializable
enum class UserKind {
    PERSON,
    TRACKER
}

@Serializable
@Entity
/** A person (or DEV_BUILD tracker) this device shares location with. */
data class User(
    val name: String,
    val photo: String?,
    var locationName: String,
    val sendingEnabled: Boolean,
    val requestStatus: RequestStatus,
    val lastLocationChangeTime: Instant = Clock.System.now(),
    val encryptionKey: String? = null,

    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val lastWaypointId: Long? = null,
    /** Peer device platform (`"android"` or `"ios"`), learned from heartbeat payloads.
     * Null until first heartbeat after both sides upgrade. */
    val platform: String? = null,
    /** When to auto-toggle sharing (flip sendingEnabled). Null means Never / disabled. Single field. */
    val sharingAutoToggleAt: Instant? = null,
    /** Waypoint whose arrival (by "Me") flips sendingEnabled. Null means no arrival trigger.
     * Mutually exclusive with [sharingAutoToggleAt]: setting one clears the other. */
    val sharingAutoToggleWaypointId: Long? = null,
    /** Peer post-quantum public bundle (base64: [4B kemLen][kemPubDer][dsaPubDer]), nullable for backward compat. */
    val pqcEncryptionKey: String? = null,
    /**
     * Hidden per-peer sender-auth secrets (GHSA-x94x-v6f7-wwpp).
     *
     * Encryption to the peer's bundle proves who a packet is *for*, not who it is *from*,
     * and the userid inside the plaintext is self-declared. Each direction holds its own
     * 128-bit secret (base64url, unpadded, ~22 chars) that never leaves the ciphertext:
     *  - [outboundPairId]: what this device puts in every packet it sends to this peer.
     *    Created once at explicit connect time; generated lazily on first send for
     *    pre-existing rows.
     *  - [inboundPairId]: what this device expects from this peer; pinned
     *    trust-on-first-use on the first packet carrying an ID. Null = unpinned/legacy.
     *
     * Never user-visible, never logged in full, never sent outside the encrypted payload.
     */
    val inboundPairId: String? = null,
    val outboundPairId: String? = null,
    /** Whether this row is a person contact or a custom UWB tracker. Defaults to
     * [UserKind.PERSON]; only the DEV_BUILD tracker feature ever writes [UserKind.TRACKER]. */
    val kind: UserKind = UserKind.PERSON
): DatabaseItem {
    /** Shared placeholder values for [User]. */
    companion object {
        val EMPTY = User(" ", null, "Unnamed Location", true, RequestStatus.MUTUAL_CONNECTION, Clock.System.now(), null)
    }
}
