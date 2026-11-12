package com.vayunmathur.findfamily.uwb

import kotlinx.serialization.Serializable

/**
 * Wire format for UWB session-setup messages sent through the existing
 * E2E-encrypted UWB endpoints.
 *
 * Only the small handshake (request / ack / config / cancel) flows over the
 * server; all RangingResult samples flow peer-to-peer over the UWB radio
 * after the session is established.
 *
 * (NB: avoid writing literal `/ api / uwb / *` patterns in KDoc blocks —
 * Kotlin's block comments are nestable, so the embedded `slash-star` opens
 * a nested comment that breaks the whole file.)
 */
@Serializable
data class UwbEnvelope(
    /** Random UUID identifying this ranging session end-to-end. */
    val sessionId: String,
    /** The userid (signed long, encoded as ULong on the wire) of the sender. */
    val sender: ULong,
    /** Sender's platform tag: `"android"` or `"ios"`. */
    val senderPlatform: String,
    /** Envelope kind: `"request"`, `"ack"`, `"config"`, `"cancel"`. */
    val kind: String,
    /** Optional payload — see [UwbHandshake]. */
    val payload: UwbHandshake? = null,
    /**
     * Powered-off recovery key material, for the [UwbEnvelopeKind.POF_GRANT] kind only.
     * Null on every UWB envelope.
     *
     * This rides the UWB channel rather than getting a channel of its own because that channel is
     * already end-to-end encrypted to the peer's ML-KEM bundle, already routed by the relay into
     * a queue the relay cannot read, and needs no server change to carry a new kind. The name is
     * now wrong for what the envelope carries; a second flag bit would have been cleaner but the
     * relay maps every unset bit0 to the *location* queue (`ff_kind`, findfamily.rs), where an
     * iOS peer would drain it and fail to parse it as a location.
     */
    val recovery: PoweredOffGrant? = null,
    /**
     * Why a [UwbEnvelopeKind.CANCEL] was sent, one of [UwbCancelReason]; null everywhere else.
     *
     * Optional and additive, the same way [recovery] is: null is a plain cancel (user stopped,
     * screen closed), which is all a peer that predates this field ever sends. Defaults are not
     * encoded, so a plain cancel is byte-identical on the wire to what older builds produce, and
     * an older peer that does get a reason drops the unknown key (`ignoreUnknownKeys` on
     * Android, `JSONDecoder` on iOS) and treats it as the plain cancel it already understands.
     */
    val reason: String? = null,
)

/** Values for [UwbEnvelope.reason]. Unknown values must be treated as a plain cancel. */
object UwbCancelReason {
    /**
     * The receiving phone can't do Find Nearby at all (below Android 16 or no UWB radio), so it
     * declined the REQUEST on arrival instead of letting the requester wait out its timeout.
     */
    const val UNSUPPORTED = "unsupported"
}

/**
 * The keys a peer needs to find this device once it is switched off, sealed to that peer's own
 * public bundle before it ever reaches the relay.
 *
 * Both halves are necessary and neither is sufficient:
 *  - [secretB64] is the beacon secret, which drives [com.vayunmathur.findfamily.tracker.PoweredOffProtocol.recentHandles]
 *    and so decides *what to ask the relay for*. It is also, unavoidably, the ability to derive
 *    this device's EIDs and recognise it in the wild.
 *  - [recoveryPrivB64] is the raw ML-KEM private DER that opens the sightings. Deliberately the
 *    dedicated recovery key and never `ff_pqcKemPriv`, which would hand over every live location
 *    this user publishes rather than only their powered-off sightings.
 *
 * [epoch] exists because delivery is asynchronous and rotation is not: a redistribution triggered
 * by a revoke can overtake an older grant still in the queue, and a peer that stored the older
 * one last would sit there polling with a dead key. Recipients keep the highest epoch they have
 * seen and drop anything below it.
 *
 * [sigB64] authenticates the sender, which the channel itself does not — see
 * [com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes] for why an unsigned grant
 * would let one connected peer put forged locations on the map under another's name.
 */
@Serializable
data class PoweredOffGrant(
    val epoch: Long,
    val secretB64: String,
    val recoveryPrivB64: String,
    val sigB64: String,
)

/**
 * Cross-platform UWB handshake payload. Fields are populated based on the
 * pairing — iOS↔iOS uses [discoveryTokenB64], Android↔Android uses the FiRa
 * fields, Android↔iOS uses the accessory-protocol fields.
 *
 * All fields are nullable so a single struct works for every pairing and the
 * JSON decoder tolerates older senders.
 */
@Serializable
data class UwbHandshake(
    // --- Android↔Android FiRa fields ---
    /** Local UWB MAC address of the sender (2 bytes), base64-encoded. */
    val addressB64: String? = null,
    /** FiRa complex-channel number chosen by the controller. */
    val channelNumber: Int? = null,
    /** FiRa preamble index chosen by the controller. */
    val preambleIndex: Int? = null,
    /** Random 8-byte session key chosen by the controller, base64-encoded. */
    val sessionKeyB64: String? = null,
    /** FiRa session id (Int) chosen by the controller. */
    val sessionId: Int? = null,

    // --- iOS↔iOS NearbyInteraction fields ---
    /** NIDiscoveryToken archived via NSKeyedArchiver, base64. */
    val discoveryTokenB64: String? = null,

    // --- Cross-platform (Phase 5) Apple accessory-protocol fields ---
    /** "accessoryData" sent Android → iOS at session start. */
    val accessoryConfigDataB64: String? = null,
    /** "shareableConfigurationData" sent iOS → Android. */
    val shareableConfigDataB64: String? = null,
)

object UwbEnvelopeKind {
    const val REQUEST = "request"
    const val ACK = "ack"
    const val CONFIG = "config"
    const val CANCEL = "cancel"

    /**
     * Powered-off recovery key delivery, carried in [UwbEnvelope.recovery].
     *
     * Additive on purpose. Every existing consumer matches these kinds by equality — Android's
     * [UwbInbox] caches only REQUEST/CANCEL, `UwbSessionManager` waits on a matching `sessionId`,
     * and iOS `UwbInbox.swift` acts only on request/cancel — so a peer that predates this drops
     * the envelope on the floor and behaves exactly as it does today. Swift's `JSONDecoder`
     * likewise ignores the unknown `recovery` key rather than failing the whole decode.
     *
     * There is deliberately **no matching "forget these keys" message**. It would be a remote
     * delete triggered by an unauthenticated `sender` field, so any connected peer could wipe the
     * keys a third party had sent us; and it would buy nothing, because revoking rotates both the
     * recovery keypair and the beacon secret. A revoked peer keeps bytes that no longer decrypt
     * anything and no longer derive any live identifier.
     */
    const val POF_GRANT = "pofgrant"
}
