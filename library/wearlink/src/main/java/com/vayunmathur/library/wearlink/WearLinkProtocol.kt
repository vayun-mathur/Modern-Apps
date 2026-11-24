package com.vayunmathur.library.wearlink

import java.util.UUID

/**
 * WearLink wire protocol v1 — first-party replacement for the Play-services
 * Wearable Data Layer. See README.md in this module for the full design.
 *
 * Transport: one Bluetooth Classic RFCOMM stream per peer pair. RFCOMM (not
 * BLE GATT) is the session transport because it is a plain ordered byte
 * stream: APK-sized transfers need no MTU chunk negotiation, no
 * characteristic/descriptor plumbing, and flow control is the socket's.
 * BLE is not used at all — the peer is already BT-bonded by Wear OS, we look
 * it up in the bonded set, so there is no scan and no advertisement (hence
 * no BLUETOOTH_SCAN/ADVERTISE and no location-permission workaround).
 *
 * Both sides run an accept loop on [SERVICE_UUID] and both sides dial bonded
 * devices, so either side can initiate. Simultaneous dials are resolved by
 * the handshake instance-id tie-break (see [WearLinkHandshake]).
 */
object WearLinkProtocol {
    /** RFCOMM service record UUID ("MAWL" + v1). Fixed: changing it is a new protocol. */
    val SERVICE_UUID: UUID = UUID.fromString("4d41574c-0001-1000-8000-00805f9b34fb")

    const val SERVICE_NAME = "ModernApps WearLink"
    const val PROTOCOL_VERSION = 1

    /** Largest framed payload. Data/stream bodies chunk well under this. */
    const val MAX_FRAME_PAYLOAD = 64 * 1024

    /** Largest single message body (messages are commands, not documents). */
    const val MAX_MESSAGE_BYTES = 256 * 1024

    /** Largest data item (path-keyed state document) after reassembly. */
    const val MAX_DATA_ITEM_BYTES = 4 * 1024 * 1024

    /** Body bytes per DATA frame chunk. */
    const val DATA_CHUNK_BYTES = 16 * 1024

    /** Body bytes per STREAM frame chunk. */
    const val STREAM_CHUNK_BYTES = 16 * 1024

    /** Largest stream (APK) accepted; mirrors the appstore watch storage cap. */
    const val MAX_STREAM_BYTES = 200L * 1024 * 1024
}

/** Frame kinds, mirroring the Data Layer surfaces they replace. */
enum class FrameType(val code: Int) {
    HELLO(1),
    HELLO_ACK(2),
    /** Fire-and-forget or ack-requested message (MessageClient). */
    MESSAGE(3),
    /** Correlated reply to a MESSAGE sent with an id expecting a response. */
    RESPONSE(4),
    /** Acknowledgement of a MESSAGE id. */
    MESSAGE_ACK(5),
    /** One chunk of a path-keyed state document (DataClient). */
    DATA(6),
    /** Stream header: total size + SHA-256 + optional resume (ChannelClient). */
    STREAM_OPEN(7),
    /** Raw stream bytes; frame id is the transfer id. */
    STREAM_CHUNK(8),
    /** End of stream; receiver verifies size + SHA-256 before completing. */
    STREAM_CLOSE(9),
    /** Receiver's durable received offset for [transferId]; enables resume. */
    STREAM_ACK(10),
    PING(11),
    PONG(12),
    ERROR(13),
    ;

    companion object {
        fun fromCode(code: Int): FrameType? = entries.firstOrNull { it.code == code }
    }
}

/** One framed unit. [body] is type-specific; see the codec helpers. */
data class WearLinkFrame(
    val type: FrameType,
    val id: Long,
    val path: String = "",
    val body: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WearLinkFrame) return false
        return type == other.type && id == other.id && path == other.path &&
            body.contentEquals(other.body)
    }

    override fun hashCode(): Int {
        var r = type.hashCode()
        r = 31 * r + id.hashCode()
        r = 31 * r + path.hashCode()
        r = 31 * r + body.contentHashCode()
        return r
    }
}

/** Connection lifecycle, surfaced to each app's existing sync state. */
sealed interface WearLinkState {
    data object Stopped : WearLinkState
    data object PermissionMissing : WearLinkState
    data object BluetoothOff : WearLinkState
    data object NoBondedPeer : WearLinkState
    data class Connecting(val peerLabel: String) : WearLinkState
    data class Connected(val peerLabel: String) : WearLinkState
    data class Failed(val reason: String) : WearLinkState
}
