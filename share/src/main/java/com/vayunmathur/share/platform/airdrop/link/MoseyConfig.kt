package com.vayunmathur.share.platform.airdrop.link

import java.io.ByteArrayOutputStream
import java.net.Inet6Address

/**
 * The opaque config blobs the Mosey HAL takes, hand-encoded as the protobuf-lite messages
 * Mosey builds (`btk` for `start`, `btn` for `update`, `btc` for the capability reply).
 *
 * Field numbers and the defaults below are recovered from Mosey's schema strings and its
 * Phenotype fallbacks (`dlr`). Live Phenotype values on a Pixel can differ; these are what a
 * Mosey build with no server config sends.
 */
object MoseyConfig {

    // AWDL channel numbers (Wi-Fi channel indices, not frequencies).
    private const val CHANNEL_149 = 149
    private const val CHANNEL_44 = 44
    private const val CHANNEL_6_2_4GHZ = 6

    /** Mosey's default AWDL channels when nothing rules them out (`byt`). */
    val DEFAULT_CHANNELS = intArrayOf(CHANNEL_149, CHANNEL_44)

    /** Its 2.4 GHz fallback when no 5 GHz channel is usable. */
    val FALLBACK_CHANNELS = intArrayOf(CHANNEL_6_2_4GHZ)

    private const val BANDWIDTH_20MHZ = 0
    private const val BANDWIDTH_80MHZ = 2
    private const val BANDWIDTH_INVALID = -1

    /** `btm` constants as they land on the wire (`btm.a(i) = i - 2`). */
    private const val UPDATE_STA_CHANNEL = 1
    private const val UPDATE_TRANSFER_STARTED = 2
    private const val UPDATE_TRANSFER_ENDED = 3

    /** Six `btj` "DATA" entries, one per Phenotype-selected power profile slot. */
    private const val POWER_PROFILE_SLOTS = 6

    // `btk` (startMoseyConfig) field numbers, in Mosey's schema order.
    private const val START_FIELD_POWER_PROFILES = 8
    private const val START_FIELD_BANDWIDTH = 9
    private const val START_FIELD_E = 10
    private const val START_FIELD_D = 11
    private const val START_FIELD_A = 12
    private const val START_FIELD_O = 13
    private const val START_FIELD_G = 14
    private const val START_FIELD_F = 16
    private const val START_FIELD_K = 18
    private const val START_FIELD_H = 19
    private const val START_FIELD_M = 20
    private const val START_FIELD_J = 21
    private const val START_FIELD_L = 22
    private const val START_FIELD_I = 23
    private const val START_FIELD_N = 24
    private const val START_FIELD_Z = 25

    // `btk` Phenotype fallback values Mosey sends with no server config.
    private const val START_VALUE_TEN = 10
    private const val START_VALUE_G = 4
    private const val START_VALUE_BUFFER = 8192
    private const val START_VALUE_M = 16
    private const val START_VALUE_32 = 32
    private const val START_VALUE_I = 64
    private const val START_VALUE_N = 16_777_216

    // `btn` (updateMoseyConfig) field numbers.
    private const val UPDATE_FIELD_PEER_ADDRESS = 3

    // Protobuf wire format: tag = (field shl 3) or wireType; varints are base-128.
    private const val FIELD_TAG_SHIFT = 3
    private const val WIRE_TYPE_MASK = 7L
    private const val WIRE_TYPE_LENGTH_DELIMITED = 2
    private const val CHANNEL_HOPPING_FIELD_NUMBER = 1L
    private const val VARINT_DATA_MASK = 0x7F
    private const val VARINT_DATA_MASK_LONG = 0x7FL
    private const val VARINT_CONTINUATION_BIT = 0x80
    private const val VARINT_CONTINUATION_BIT_LONG = 0x80L
    private const val VARINT_SHIFT_BITS = 7

    /**
     * `startMoseyConfig` (`btk`, built in `byt.m`).
     *
     * [staChannel] is the infrastructure channel the phone is associated on, or null when not
     * connected; [dualBandSimultaneous] is `WifiManager.isDualBandSimultaneousSupported()`.
     */
    fun start(channels: IntArray, staChannel: Int?, dualBandSimultaneous: Boolean): ByteArray = Proto().apply {
        bool(1, dualBandSimultaneous)
        int32(2, staChannel ?: 0)
        // 3..6: Phenotype booleans x(), y(), w() (channel hopping), B(), all false by default.
        // 7: bti enum from c() = 0, which maps to wire 0 and is omitted.
        packedEnums(START_FIELD_POWER_PROFILES, IntArray(POWER_PROFILE_SLOTS))
        int32(START_FIELD_BANDWIDTH, bandwidthFor(channels))
        int32(START_FIELD_E, START_VALUE_TEN) // e()
        int32(START_FIELD_D, START_VALUE_TEN) // d()
        int32(START_FIELD_A, START_VALUE_TEN) // a()
        int32(START_FIELD_O, 2) // o()
        int32(START_FIELD_G, START_VALUE_G) // g()
        // 15: STA links (repeated bsp); left empty, `d` already carries the STA channel.
        int32(START_FIELD_F, START_VALUE_TEN) // f()
        // 17: A() false
        int32(START_FIELD_K, START_VALUE_BUFFER) // k()
        int32(START_FIELD_H, START_VALUE_BUFFER) // h()
        int32(START_FIELD_M, START_VALUE_M) // m()
        int32(START_FIELD_J, START_VALUE_32) // j()
        int32(START_FIELD_L, START_VALUE_32) // l()
        int32(START_FIELD_I, START_VALUE_I) // i()
        int32(START_FIELD_N, START_VALUE_N) // n()
        bool(START_FIELD_Z, true) // z()
        // 26: v() false; 27: bth enum from b() = 0, omitted.
    }.bytes()

    /** `updateMoseyConfig` telling the daemon a transfer to [peer] began. */
    fun transferStarted(peer: Inet6Address): ByteArray = transfer(UPDATE_TRANSFER_STARTED, peer)

    fun transferEnded(peer: Inet6Address): ByteArray = transfer(UPDATE_TRANSFER_ENDED, peer)

    /** `updateMoseyConfig` reporting the infrastructure channel moved (or was lost). */
    fun staChannelUpdate(staChannel: Int?): ByteArray = Proto().apply {
        int32(1, staChannel ?: 0)
        int32(2, UPDATE_STA_CHANNEL)
    }.bytes()

    private fun transfer(kind: Int, peer: Inet6Address): ByteArray = Proto().apply {
        int32(2, kind)
        bytes(UPDATE_FIELD_PEER_ADDRESS, peer.address)
    }.bytes()

    /** `btc.b`: whether the radio can hop between the AWDL and infrastructure channels. */
    fun isChannelHoppingSupported(capability: ByteArray?): Boolean {
        if (capability == null) return false
        var i = 0
        while (i < capability.size) {
            val (key, next) = readVarint(capability, i)
            i = next
            when ((key and WIRE_TYPE_MASK).toInt()) {
                0 -> {
                    val (value, after) = readVarint(capability, i)
                    if (key ushr FIELD_TAG_SHIFT == CHANNEL_HOPPING_FIELD_NUMBER) return value != 0L
                    i = after
                }
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val (len, after) = readVarint(capability, i)
                    i = after + len.toInt()
                }
                else -> return false
            }
        }
        return false
    }

    private fun bandwidthFor(channels: IntArray): Int = when {
        channels.contentEquals(FALLBACK_CHANNELS) -> BANDWIDTH_20MHZ
        CHANNEL_6_2_4GHZ in channels ||
            (CHANNEL_149 !in channels && CHANNEL_44 !in channels) -> BANDWIDTH_INVALID
        else -> BANDWIDTH_80MHZ
    }

    private fun readVarint(b: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size) {
            val byte = b[i++].toInt()
            result = result or ((byte and VARINT_DATA_MASK).toLong() shl shift)
            if (byte and VARINT_CONTINUATION_BIT == 0) return result to i
            shift += VARINT_SHIFT_BITS
        }
        return result to i
    }

    /** Minimal proto3 writer: zero/false/empty values are omitted, as protobuf-lite does. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        fun bool(field: Int, value: Boolean) {
            if (value) int32(field, 1)
        }

        fun int32(field: Int, value: Int) {
            if (value == 0) return
            varint((field shl FIELD_TAG_SHIFT).toLong())
            // Negative int32/enum values are sign-extended to ten bytes on the wire.
            varint(value.toLong())
        }

        fun bytes(field: Int, value: ByteArray) {
            if (value.isEmpty()) return
            varint(((field shl FIELD_TAG_SHIFT) or WIRE_TYPE_LENGTH_DELIMITED).toLong())
            varint(value.size.toLong())
            out.write(value)
        }

        fun packedEnums(field: Int, values: IntArray) {
            if (values.isEmpty()) return
            val body = Proto().apply { values.forEach { varint(it.toLong()) } }.bytes()
            bytes(field, body)
        }

        fun varint(value: Long) {
            var v = value
            while (v and VARINT_DATA_MASK_LONG.inv() != 0L) {
                out.write(((v and VARINT_DATA_MASK_LONG) or VARINT_CONTINUATION_BIT_LONG).toInt())
                v = v ushr VARINT_SHIFT_BITS
            }
            out.write(v.toInt())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
