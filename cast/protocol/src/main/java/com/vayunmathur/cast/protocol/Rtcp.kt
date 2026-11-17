package com.vayunmathur.cast.protocol

/**
 * RTCP, both directions.
 *
 * The sender builds sender reports and parses feedback; the receiver builds feedback and parses
 * sender reports. Every builder here has its parser in the same file **on purpose** - that pairing
 * is what makes a disagreement about the wire format a failing unit test instead of a black screen
 * on a TV across the room.
 *
 * The layouts come from openscreen `cast/streaming/impl/rtcp_common.cc` (the common header),
 * `impl/sender_report_builder.cc` (the sender report) and `impl/compound_rtcp_parser.cc`
 * (`ParseFeedback`, the ACK/NACK). The wire diagrams are in `impl/rtp_defines.h`.
 *
 * Getting this wrong does not look like an error: the picture renders and then freezes, because
 * without sender reports the receiver has no clock to schedule against, and without feedback
 * nothing is ever retransmitted.
 */
object Rtcp {

    /** Version 2, no padding, in the top three bits of byte 0. */
    private const val VERSION_AND_PADDING = 0b100 shl 5

    /** `RtcpPacketType` values. */
    private const val TYPE_SENDER_REPORT = 200

    /**
     * `kPayloadSpecific`. **This**, not the application-defined 204, is what carries feedback:
     * `CompoundRtcpParser::Parse` dispatches `kFeedback` and `kPictureLossIndicator` from 206, and
     * uses 204 only for receiver log messages.
     */
    private const val TYPE_PAYLOAD_SPECIFIC = 206

    /** `RtcpSubtype` values, carried in the low five bits of byte 0. */
    private const val SUBTYPE_PICTURE_LOSS_INDICATOR = 1
    private const val SUBTYPE_FEEDBACK = 15

    private const val COMMON_HEADER_SIZE = 4

    /** `kRtcpSenderReportSize`, excluding the 4-byte common header. */
    private const val SENDER_REPORT_SIZE = 24

    /** `'CAST'` and `'CST2'` as big-endian ASCII words. */
    private const val CAST_WORD = 0x43_41_53_54L
    private const val CST2_WORD = 0x43_53_54_32L

    private const val FEEDBACK_HEADER_SIZE = 16
    private const val FEEDBACK_ACK_HEADER_SIZE = 6
    private const val LOSS_FIELD_SIZE = 4
    private const val SSRC_PAIR_SIZE = 8

    private const val ACK_VECTOR_BASE_OFFSET = 2L
    private const val PLI_BODY_WORDS = 2

    private const val BYTE_MASK = 0xffL
    private const val BYTE_MASK_INT = 0xff
    private const val SHORT_MASK = 0xffff_ffffL
    private const val NTP_HIGH_SHIFT = 32
    private const val WORD_BYTES = 4
    private const val VERSION_BITS = 0b1110_0000
    private const val SUBTYPE_BITS = 0b0001_1111
    private const val LOSS_VECTOR_SPAN = 8
    private const val ACK_BITS_PER_OCTET = 8L
    private const val WRAP_RANGE = 256
    private const val HALF_WRAP = 128
    private const val SENDER_REPORT_NTP_HIGH = 4
    private const val SENDER_REPORT_NTP_LOW = 8
    private const val SENDER_REPORT_RTP = 12
    private const val SENDER_REPORT_PACKETS = 16
    private const val SENDER_REPORT_OCTETS = 20

    /** `kAllPacketsLost` - a NACK for a whole frame rather than for one packet. */
    const val ALL_PACKETS_LOST = 0xffff

    /**
     * How many loss fields one feedback packet may carry.
     *
     * The wire allows 255, but a receiver in a bad moment can be missing far more than that, and a
     * NACK list long enough to fragment the datagram makes the loss worse rather than better. The
     * frames nearest the checkpoint are the ones blocking playout, so those are the ones that fit.
     */
    const val MAX_LOSS_FIELDS = 32

    // ---- sender -> receiver ----

    /**
     * A sender report: our clock, and how much we have sent.
     *
     * [ntpTimestamp] is the 64-bit NTP form of the moment [rtpTimestamp] was captured; the pair is
     * what lets the receiver relate our media clock to wall time.
     */
    fun senderReport(
        senderSsrc: Long,
        ntpTimestamp: Long,
        rtpTimestamp: Long,
        packetCount: Long,
        octetCount: Long,
    ): ByteArray {
        val out = ByteArray(COMMON_HEADER_SIZE + SENDER_REPORT_SIZE)
        var i = 0
        out[i++] = VERSION_AND_PADDING.toByte() // report_count = 0: we send no report blocks
        out[i++] = TYPE_SENDER_REPORT.toByte()
        // The length field counts 32-bit words *after* the first, which is what dividing by 4
        // without adding one expresses.
        i = out.putShort(i, SENDER_REPORT_SIZE / WORD_BYTES)
        i = out.putInt(i, senderSsrc and SHORT_MASK)
        i = out.putInt(i, (ntpTimestamp ushr NTP_HIGH_SHIFT) and SHORT_MASK)
        i = out.putInt(i, ntpTimestamp and SHORT_MASK)
        i = out.putInt(i, rtpTimestamp and SHORT_MASK)
        i = out.putInt(i, packetCount and SHORT_MASK)
        out.putInt(i, octetCount and SHORT_MASK)
        return out
    }

    /**
     * The receiver's half of [senderReport].
     *
     * Walks the compound datagram rather than reading only the first sub-packet, for the same
     * reason [parse] does. Returns null when nothing in it is a sender report for [senderSsrc].
     */
    fun parseSenderReport(packet: ByteArray, senderSsrc: Long): SenderReport? {
        forEachSubPacket(packet) { type, _, start, size ->
            if (type == TYPE_SENDER_REPORT && size >= SENDER_REPORT_SIZE) {
                if (packet.getInt(start) == senderSsrc and SHORT_MASK) {
                    return SenderReport(
                        ntpTimestamp =
                            (packet.getInt(start + SENDER_REPORT_NTP_HIGH) shl NTP_HIGH_SHIFT) or
                                packet.getInt(start + SENDER_REPORT_NTP_LOW),
                        rtpTimestamp = packet.getInt(start + SENDER_REPORT_RTP),
                        packetCount = packet.getInt(start + SENDER_REPORT_PACKETS),
                        octetCount = packet.getInt(start + SENDER_REPORT_OCTETS),
                    )
                }
            }
        }
        return null
    }

    // ---- receiver -> sender ----

    /**
     * The feedback [parse] reads: what the receiver has, and what it is missing.
     *
     * [nacks] is a flat list of individual missing packets; coalescing them into loss fields and
     * their 8-bit follow-on vectors happens here, so a caller never has to know that the wire has a
     * bit vector at all. [ackedFrames] must all be above [checkpoint], since everything up to it is
     * acknowledged by the checkpoint itself.
     */
    fun feedback(
        receiverSsrc: Long,
        senderSsrc: Long,
        checkpoint: FrameId,
        playoutDelayMs: Int,
        nacks: List<PacketNack> = emptyList(),
        ackedFrames: List<FrameId> = emptyList(),
    ): ByteArray {
        val lossFields = lossFields(nacks)
        val ackOctets = ackOctets(checkpoint, ackedFrames)
        val bodySize = FEEDBACK_HEADER_SIZE +
            LOSS_FIELD_SIZE * lossFields.size +
            if (ackOctets.isEmpty()) 0 else FEEDBACK_ACK_HEADER_SIZE + ackOctets.size
        // The length field counts whole 32-bit words, so the body is padded up to one.
        val padded = (bodySize + 3) / 4 * 4
        val out = ByteArray(COMMON_HEADER_SIZE + padded)
        var i = 0
        out[i++] = (VERSION_AND_PADDING or SUBTYPE_FEEDBACK).toByte()
        out[i++] = TYPE_PAYLOAD_SPECIFIC.toByte()
        i = out.putShort(i, padded / WORD_BYTES)
        i = out.putInt(i, receiverSsrc and SHORT_MASK)
        i = out.putInt(i, senderSsrc and SHORT_MASK)
        i = out.putInt(i, CAST_WORD)
        out[i++] = checkpoint.lower8.toByte()
        out[i++] = lossFields.size.toByte()
        i = out.putShort(i, playoutDelayMs)
        for (field in lossFields) {
            out[i++] = field.frameId.lower8.toByte()
            i = out.putShort(i, field.packetId)
            out[i++] = field.followingBits.toByte()
        }
        if (ackOctets.isNotEmpty()) {
            i = out.putInt(i, CST2_WORD)
            out[i++] = 0 // Feedback Count: openscreen sends a counter here; nothing reads it.
            out[i++] = ackOctets.size.toByte()
            for (octet in ackOctets) out[i++] = octet
        }
        return out
    }

    /**
     * "Send me a key frame": the receiver cannot decode anything it currently holds.
     *
     * A separate sub-packet rather than a flag on [feedback], because that is where
     * `CompoundRtcpParser` looks for it - and because it is the one message worth sending on its own
     * when there is no useful checkpoint to report yet.
     */
    fun pictureLossIndicator(receiverSsrc: Long, senderSsrc: Long): ByteArray {
        val out = ByteArray(COMMON_HEADER_SIZE + 8)
        var i = 0
        out[i++] = (VERSION_AND_PADDING or SUBTYPE_PICTURE_LOSS_INDICATOR).toByte()
        out[i++] = TYPE_PAYLOAD_SPECIFIC.toByte()
        i = out.putShort(i, PLI_BODY_WORDS)
        i = out.putInt(i, receiverSsrc and SHORT_MASK)
        out.putInt(i, senderSsrc and SHORT_MASK)
        return out
    }

    /**
     * Concatenate sub-packets into one datagram.
     *
     * RTCP is compound by design, and sending a PLI and a feedback block as two datagrams doubles
     * the chance of losing the one that mattered.
     */
    fun compound(vararg packets: ByteArray): ByteArray {
        val out = ByteArray(packets.sumOf { it.size })
        var offset = 0
        for (packet in packets) {
            packet.copyInto(out, offset)
            offset += packet.size
        }
        return out
    }

    /**
     * Turn individual missing packets into loss fields.
     *
     * Each field names one packet id and then carries eight bits for the eight ids after it, so a
     * run of loss inside one frame costs four bytes rather than four per packet. A whole-frame NACK
     * is one field with [ALL_PACKETS_LOST] and no vector, and it subsumes anything else for that
     * frame - which is also exactly how `StreamingSession` reads it back.
     */
    private fun lossFields(nacks: List<PacketNack>): List<LossField> {
        if (nacks.isEmpty()) return emptyList()
        // Grouped and sorted so the frames closest to the checkpoint - the ones actually blocking
        // playout - are the ones that survive MAX_LOSS_FIELDS.
        val byFrame = nacks.groupBy { it.frameId }.toSortedMap()
        val all = byFrame.flatMap { (frameId, forFrame) -> fieldsForFrame(frameId, forFrame) }
        return all.take(MAX_LOSS_FIELDS)
    }

    private fun fieldsForFrame(frameId: FrameId, forFrame: List<PacketNack>): List<LossField> {
        if (forFrame.any { it.isWholeFrame }) {
            return listOf(LossField(frameId, ALL_PACKETS_LOST, 0))
        }
        val ids = forFrame.map { it.packetId }.distinct().sorted()
        val out = mutableListOf<LossField>()
        var index = 0
        while (index < ids.size) {
            val base = ids[index]
            var bits = 0
            var next = index + 1
            while (next < ids.size && ids[next] - base <= LOSS_VECTOR_SPAN) {
                bits = bits or (1 shl (ids[next] - base - 1))
                next++
            }
            out += LossField(frameId, base, bits)
            index = next
        }
        return out
    }

    /**
     * The CST2 frame-ACK bit vector.
     *
     * Bit *i* of octet *j* is frame `checkpoint + 2 + 8*j + i`: the "plus two" is openscreen's,
     * documented in `rtp_defines.h`, because the checkpoint frame is implicitly acked and the vector
     * begins beyond the frame following it.
     */
    private fun ackOctets(checkpoint: FrameId, ackedFrames: List<FrameId>): ByteArray {
        val offsets = ackedFrames
            .map { it - checkpoint - ACK_VECTOR_BASE_OFFSET }
            // The vector's length is written into a single byte, so a frame further ahead than it can
            // reach simply is not acked. Nothing is lost by that: an ack is an optimisation, and the
            // checkpoint already says everything that has definitely been played.
            .filter { it in 0 until MAX_ACK_OCTETS * ACK_BITS_PER_OCTET }
        val highest = offsets.maxOrNull() ?: return ByteArray(0)
        val out = ByteArray((highest / ACK_BITS_PER_OCTET + 1).toInt())
        for (offset in offsets) {
            val octet = (offset / ACK_BITS_PER_OCTET).toInt()
            val bit = (offset % ACK_BITS_PER_OCTET).toInt()
            out[octet] = (out[octet].toInt() or (1 shl bit)).toByte()
        }
        return out
    }

    /** The ACK bit vector's length field is one byte. */
    private const val MAX_ACK_OCTETS = 255

    private data class LossField(val frameId: FrameId, val packetId: Int, val followingBits: Int)

    // ---- parsing feedback (the sender's side) ----

    /**
     * Parse a datagram of RTCP from the receiver.
     *
     * **RTCP is compound**: one datagram is the concatenation of several RTCP packets, so reading
     * only the first sub-packet finds feedback essentially never - which looks exactly like a
     * receiver that is not talking to us at all.
     *
     * [maxFrameId] is the highest frame we have sent; the checkpoint arrives truncated to 8 bits
     * and is expanded against it, exactly as `ExpandLessThanOrEqual` does. Without that the
     * checkpoint would appear to jump backwards every 256 frames.
     *
     * **Every sub-packet is checked against this stream's SSRC pair, the PLI included.** A receiver
     * sends one datagram per stream, and the sender tries each stream in turn until one parses. A PLI
     * accepted without that check made the *audio* stream claim a datagram carrying video's key-frame
     * request: the audio stream ignores picture loss, and the sender stopped looking, so the video
     * stream never saw its own feedback. Measured: a receiver PLI-ing for ten seconds while the sender
     * logged not one key-frame request, and no video NACKs answered either, because exactly the
     * datagrams sent when the receiver was in trouble were the ones thrown away.
     *
     * Returns null when the datagram contains nothing addressed to this stream.
     */
    fun parse(
        packet: ByteArray,
        receiverSsrc: Long,
        senderSsrc: Long,
        maxFrameId: FrameId,
    ): ReceiverFeedback? {
        var feedback: ReceiverFeedback? = null
        var pictureLoss = false
        forEachSubPacket(packet) { type, countOrSubtype, start, size ->
            if (type == TYPE_PAYLOAD_SPECIFIC) {
                when (countOrSubtype) {
                    SUBTYPE_FEEDBACK -> parseFeedbackPayload(
                        packet,
                        start,
                        size,
                        receiverSsrc,
                        senderSsrc,
                        maxFrameId,
                    )?.let { feedback = it }
                    SUBTYPE_PICTURE_LOSS_INDICATOR ->
                        if (namesStream(packet, start, size, receiverSsrc, senderSsrc)) {
                            pictureLoss = true
                        }
                }
            }
        }
        val parsed = feedback
        return when {
            parsed != null -> parsed.copy(pictureLoss = parsed.pictureLoss || pictureLoss)
            // A bare PLI with no feedback block still has to reach the encoder.
            pictureLoss -> ReceiverFeedback(
                checkpoint = maxFrameId,
                playoutDelayMs = 0,
                nacks = emptyList(),
                ackedFrames = emptyList(),
                pictureLoss = true,
            )
            else -> null
        }
    }

    /**
     * Walk a compound datagram, calling [block] with each sub-packet's type, count-or-subtype
     * nibble, payload offset and payload size.
     *
     * Stops at the first byte that is not a valid common header rather than throwing: trailing
     * padding and sub-packet types we know nothing about both end up here.
     */
    private inline fun forEachSubPacket(
        packet: ByteArray,
        block: (type: Int, countOrSubtype: Int, start: Int, size: Int) -> Unit,
    ) {
        var offset = 0
        while (offset + COMMON_HEADER_SIZE <= packet.size) {
            val byte0 = packet[offset].toInt() and BYTE_MASK_INT
            if (byte0 and VERSION_BITS != VERSION_AND_PADDING) return
            val countOrSubtype = byte0 and SUBTYPE_BITS
            val type = packet[offset + 1].toInt() and BYTE_MASK_INT
            val payloadSize = packet.getShort(offset + 2) * WORD_BYTES
            val payloadStart = offset + COMMON_HEADER_SIZE
            if (payloadStart + payloadSize > packet.size) return
            block(type, countOrSubtype, payloadStart, payloadSize)
            offset = payloadStart + payloadSize
        }
    }

    /**
     * True when a sub-packet's leading SSRC pair names this stream.
     *
     * Both the PLI and the feedback block start with receiver-then-sender, so one check serves both.
     */
    private fun namesStream(
        packet: ByteArray,
        start: Int,
        size: Int,
        receiverSsrc: Long,
        senderSsrc: Long,
    ): Boolean {
        if (size < SSRC_PAIR_SIZE) return false
        return packet.getInt(start) == receiverSsrc and SHORT_MASK &&
            packet.getInt(start + WORD_BYTES) == senderSsrc and SHORT_MASK
    }

    /**
     * One `kFeedback` payload, i.e. the bytes after its 4-byte common header.
     *
     * Returns null when the SSRC pair names another stream, which is normal: a compound datagram
     * carries feedback for whichever stream the receiver is reporting on.
     */
    private fun parseFeedbackPayload(
        packet: ByteArray,
        start: Int,
        size: Int,
        receiverSsrc: Long,
        senderSsrc: Long,
        maxFrameId: FrameId,
    ): ReceiverFeedback? {
        if (size < FEEDBACK_HEADER_SIZE) return null
        val end = start + size
        var i = start
        if (packet.getInt(i) != receiverSsrc and SHORT_MASK) return null
        i += WORD_BYTES
        if (packet.getInt(i) != senderSsrc and SHORT_MASK) return null
        i += WORD_BYTES
        if (packet.getInt(i) != CAST_WORD) return null
        i += WORD_BYTES

        val checkpoint = expandLessThanOrEqual(maxFrameId, packet[i].toInt() and BYTE_MASK_INT)
        i++
        val lossFieldCount = packet[i].toInt() and BYTE_MASK_INT
        i++
        val playoutDelayMs = packet.getShort(i)
        i += SSRC_PAIR_SIZE / WORD_BYTES

        if (end - i < LOSS_FIELD_SIZE * lossFieldCount) return null
        val nacks = mutableListOf<PacketNack>()
        repeat(lossFieldCount) {
            val frameId = expandGreaterThan(checkpoint, packet[i].toInt() and BYTE_MASK_INT)
            i++
            var packetId = packet.getShort(i)
            i += SSRC_PAIR_SIZE / WORD_BYTES
            var bits = packet[i].toInt() and BYTE_MASK_INT
            i++
            nacks += PacketNack(frameId, packetId)
            if (packetId != ALL_PACKETS_LOST) {
                // Each set bit is another missing packet, counting up from packetId.
                while (bits != 0) {
                    packetId++
                    if (bits and 1 != 0) nacks += PacketNack(frameId, packetId)
                    bits = bits ushr 1
                }
            }
        }

        // CST2 frame-level ACKs are optional, and openscreen deliberately tolerates trailing bytes
        // that are not 'CST2' rather than calling the packet corrupt.
        val acked = mutableListOf<FrameId>()
        if (end - i >= FEEDBACK_ACK_HEADER_SIZE && packet.getInt(i) == CST2_WORD) {
            i += WORD_BYTES
            i++ // Feedback Count, unused.
            val octets = packet[i].toInt() and BYTE_MASK_INT
            i++
            if (end - i >= octets) {
                var frameId = checkpoint + ACK_VECTOR_BASE_OFFSET
                repeat(octets) {
                    var bits = packet[i].toInt() and BYTE_MASK_INT
                    var id = frameId
                    while (bits != 0) {
                        if (bits and 1 != 0) acked += id
                        bits = bits ushr 1
                        id += 1
                    }
                    i++
                    frameId += ACK_BITS_PER_OCTET
                }
            }
        }
        return ReceiverFeedback(checkpoint, playoutDelayMs, nacks, acked)
    }

    // ---- id expansion ----

    /**
     * Re-expand a truncated 8-bit id, knowing the result is at most [reference].
     *
     * openscreen `impl/expanded_value_base.h`, `ExpandLessThanOrEqual`.
     */
    internal fun expandLessThanOrEqual(reference: FrameId, truncated: Int): FrameId {
        val delta = (reference.lower8 - truncated) and BYTE_MASK_INT
        return FrameId(reference.value - delta)
    }

    /** The mirror of the above, for an id known to be greater than [reference]. */
    internal fun expandGreaterThan(reference: FrameId, truncated: Int): FrameId {
        val delta = (truncated - reference.lower8) and BYTE_MASK_INT
        return FrameId(reference.value + if (delta == 0) WRAP_RANGE else delta)
    }

    /**
     * Expand to whichever full id is *nearest* [reference], in either direction.
     *
     * `ExpandNearest`. This is the one the receiver needs: an arriving frame is usually just ahead
     * of the last one completed, but a retransmission is behind it, and neither of the one-sided
     * expansions above can produce both.
     */
    internal fun expandNearest(reference: FrameId, truncated: Int): FrameId {
        val delta = ((((truncated - reference.lower8) and BYTE_MASK_INT) + HALF_WRAP) and BYTE_MASK_INT) -
            HALF_WRAP
        return FrameId(reference.value + delta)
    }
}

/** One packet the receiver says it is missing. */
data class PacketNack(val frameId: FrameId, val packetId: Int) {
    val isWholeFrame: Boolean get() = packetId == Rtcp.ALL_PACKETS_LOST

    /** Factories for [PacketNack]. */
    companion object {
        /** Nothing at all arrived for this frame, so every packet of it is missing. */
        fun wholeFrame(frameId: FrameId): PacketNack = PacketNack(frameId, Rtcp.ALL_PACKETS_LOST)
    }
}

/**
 * What the receiver told us.
 *
 * [checkpoint] is the highest frame it has completely received, so everything up to and including
 * it can be dropped from the retransmit buffer. [nacks] is what to resend.
 */
data class ReceiverFeedback(
    val checkpoint: FrameId,
    val playoutDelayMs: Int,
    val nacks: List<PacketNack>,
    val ackedFrames: List<FrameId>,
    /** The receiver sent a Picture Loss Indicator: it needs a key frame to decode anything. */
    val pictureLoss: Boolean = false,
)

/**
 * What the sender told us.
 *
 * The [ntpTimestamp]/[rtpTimestamp] pair is the only thing that relates the sender's media clock to
 * wall time, which is what a receiver needs to schedule playout rather than render as fast as
 * packets arrive.
 */
data class SenderReport(
    val ntpTimestamp: Long,
    val rtpTimestamp: Long,
    val packetCount: Long,
    val octetCount: Long,
)

internal fun ByteArray.getShort(offset: Int): Int =
    ((this[offset].toInt() and RtcpByte.MASK) shl RtcpByte.HIGH_SHIFT) or
        (this[offset + 1].toInt() and RtcpByte.MASK)

internal fun ByteArray.getInt(offset: Int): Long =
    ((this[offset].toLong() and RtcpByte.MASK_LONG) shl RtcpByte.SHIFT_3) or
        ((this[offset + RtcpByte.INDEX_1].toLong() and RtcpByte.MASK_LONG) shl RtcpByte.SHIFT_2) or
        ((this[offset + RtcpByte.INDEX_2].toLong() and RtcpByte.MASK_LONG) shl RtcpByte.SHIFT_1) or
        (this[offset + RtcpByte.INDEX_3].toLong() and RtcpByte.MASK_LONG)

private object RtcpByte {
    const val MASK = 0xff
    const val MASK_LONG = 0xffL
    const val HIGH_SHIFT = 8
    const val SHIFT_1 = 8
    const val SHIFT_2 = 16
    const val SHIFT_3 = 24
    const val INDEX_1 = 1
    const val INDEX_2 = 2
    const val INDEX_3 = 3
}
