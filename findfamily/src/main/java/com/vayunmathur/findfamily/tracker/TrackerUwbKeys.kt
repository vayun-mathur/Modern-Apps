package com.vayunmathur.findfamily.tracker

import com.vayunmathur.e2ee.Hkdf

/**
 * FiRa session material for phone↔tracker ranging, derived on both ends from the
 * bind-time beacon secret rather than sent over the air.
 *
 * The GATT link to a tracker is unbonded and unencrypted, so anything transmitted on
 * it is spoofable — a nearby attacker could hand the tracker its own session key and
 * range against it. Deriving instead means the per-find GATT write only carries
 * channel/slot params (see [TrackerUwbGatt.encodeSessionParams]), which are useless
 * without the secret.
 *
 * Pure JVM (no Android dependencies) so it is unit-testable next to [TrackerProtocol],
 * and simple enough to mirror byte-for-byte in the tracker firmware's PSA-Crypto HKDF.
 */
object TrackerUwbKeys {

    /** Length of the FiRa STS key: exactly what `UwbRangingParams.setSessionKeyInfo` takes. */
    const val STS_KEY_LEN: Int = 8

    private const val STS_INFO = "com.vayunmathur.findfamily/uwb-sts"
    private const val ADDR_INFO = "com.vayunmathur.findfamily/uwb-addr"

    /** Shifts for the big-endian u32 encoding. */
    private const val U32_MSB_SHIFT = 24
    private const val U32_MID_SHIFT = 16
    private const val U32_LOW_SHIFT = 8

    /**
     * The 8-byte static-STS key for one ranging session:
     * `HKDF-SHA256(secret, info = "…/uwb-sts" || u32_be(sessionId))`.
     *
     * Under FiRa these 8 bytes are `[2B VendorID][6B STATIC_STS_IV]`, which is what
     * `CONFIG_UNICAST_DS_TWR` expects. Mixing in [sessionId] gives every find a
     * distinct IV without either side transmitting key material.
     */
    fun stsKey(secret: ByteArray, sessionId: Int): ByteArray {
        require(secret.size == TrackerProtocol.SECRET_LEN) {
            "secret must be ${TrackerProtocol.SECRET_LEN} bytes"
        }
        return Hkdf.derive(secret, STS_INFO.toByteArray(Charsets.US_ASCII) + u32be(sessionId), STS_KEY_LEN)
    }

    /**
     * The tracker's 2-byte UWB MAC address: `HKDF-SHA256(secret, info = "…/uwb-addr")`.
     *
     * Derived rather than carried in the session-params write so the wire format stays
     * 8 bytes. Fixed per tracker, which is fine: it is only ever used inside a session
     * keyed by [stsKey], and it is never broadcast in the clear.
     *
     * The reserved FiRa addresses `0x0000` and `0xFFFF` are nudged to `..01`, the same
     * way `UwbController.openController` handles its random address.
     */
    fun uwbAddress(secret: ByteArray): ByteArray {
        require(secret.size == TrackerProtocol.SECRET_LEN) {
            "secret must be ${TrackerProtocol.SECRET_LEN} bytes"
        }
        val addr = Hkdf.derive(secret, ADDR_INFO.toByteArray(Charsets.US_ASCII), 2)
        val reserved = (addr[0] == 0x00.toByte() && addr[1] == 0x00.toByte()) ||
            (addr[0] == 0xFF.toByte() && addr[1] == 0xFF.toByte())
        if (reserved) addr[1] = 0x01
        return addr
    }

    private fun u32be(v: Int): ByteArray = byteArrayOf(
        (v ushr U32_MSB_SHIFT).toByte(),
        (v ushr U32_MID_SHIFT).toByte(),
        (v ushr U32_LOW_SHIFT).toByte(),
        v.toByte(),
    )
}
