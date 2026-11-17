package com.vayunmathur.findfamily.domain

import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import kotlinx.serialization.json.Json
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-Kotlin crypto/wire logic for the custom UWB tracker crowd-finding network.
 * No Android dependencies, so it is fully unit-testable (see TrackerProtocolTest)
 * and its byte formats can be mirrored exactly on the Rust server and on the
 * ESP32/nRF firmware.
 *
 * Crypto model (chosen tradeoff): **rotating-hash + server-served ML-KEM key**.
 *  - The tracker BLE-beacons a small rotating [epochId] derived from a per-tracker
 *    [SECRET_LEN]-byte secret it was provisioned with at bind time. No static id is
 *    ever broadcast, so a beacon can't be trivially followed by identity.
 *  - A finder resolves that epoch-id to the tracker's ML-KEM public bundle via the
 *    server, seals its own GPS to it with [sealReport], and uploads the ciphertext
 *    keyed by the epoch-id. The finder never learns whose tracker it is (the sealed
 *    location carries userid 0).
 *  - The owner recomputes [recentEpochIds] (it holds the secret), fetches matching
 *    reports, and decrypts each with [openReport] using the tracker's ML-KEM private
 *    bundle, stamping the resulting [LocationValue] with the tracker's own userid so
 *    it renders as that tracker's map pin.
 */
object TrackerProtocol {

    /** Beacon rotation period. Must equal the server's `TRACKER_EPOCH_SECS`. */
    const val EPOCH_SECONDS: Long = 900L // 15 minutes

    /** Length of the rotating beacon id advertised over BLE and used as the report key. */
    const val EPOCH_ID_LEN: Int = 16

    /** Length of the per-tracker beacon master secret provisioned at bind time. */
    const val SECRET_LEN: Int = 32

    /** Domain-separation tag mixed into every epoch-id HMAC. Must match the server. */
    private const val EPOCH_DOMAIN = "fftrk1"

    /** Milliseconds per second, for wall-clock to epoch conversion. */
    private const val MILLIS_PER_SECOND = 1000L

    /** Length of one big-endian u64 field. */
    private const val U64_LEN = 8

    /** Shift of the most significant byte in a big-endian u64. */
    private const val U64_MSB_SHIFT = 56

    /** Bits per byte, for big-endian byte-packing shifts. */
    private const val BITS_PER_BYTE = 8

    /** Length of the `[4B kemPrivLen]` prefix on a private bundle. */
    private const val KEM_LEN_PREFIX_LEN = 4

    private val json = Json { ignoreUnknownKeys = true }

    /** The current epoch number for a wall-clock time in milliseconds. */
    fun currentEpoch(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs / MILLIS_PER_SECOND) / EPOCH_SECONDS

    /**
     * The 16-byte rotating beacon id for a `(secret, epoch)` pair:
     * `HMAC-SHA256(secret, "fftrk1" || u64_be(epoch))[..16]`. Deterministic and
     * byte-identical to the server's `tracker_epoch_id`.
     */
    fun epochId(secret: ByteArray, epoch: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(EPOCH_DOMAIN.toByteArray(Charsets.US_ASCII))
        mac.update(u64be(epoch))
        return mac.doFinal().copyOf(EPOCH_ID_LEN)
    }

    /**
     * The epoch-ids the owner should query to catch recent sightings: the current
     * epoch and [back] previous ones (so late-delivered reports within roughly
     * `back * EPOCH_SECONDS` are still found).
     */
    fun recentEpochIds(
        secret: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        back: Int = 8,
    ): List<ByteArray> {
        val cur = currentEpoch(nowMs)
        return (0..back).map { epochId(secret, cur - it) }
    }

    /**
     * Finder side: seal this finder's own [finderLocation] to the tracker's ML-KEM
     * public [trackerBundle]. The userid is zeroed so the report never reveals the
     * finder's identity to the owner; the owner restamps it in [openReport].
     */
    fun sealReport(trackerBundle: ByteArray, finderLocation: LocationValue): ByteArray {
        val anon = finderLocation.copy(userid = 0L).toCompatible(senderPlatform = null)
        val str = json.encodeToString(anon)
        return Pqc.encryptTo(trackerBundle, str.encodeToByteArray())
    }

    /**
     * Owner side: decrypt a crowd report [ciphertext] with the tracker's private
     * bundle ([privateBundle], layout `[4B kemPrivLen][kemPriv][dsaPriv]`, as
     * produced by `Networking.generatePqcIdentityKeyPair`) and stamp it with the tracker's
     * [trackerUserId] so it flows through the normal incoming-location pipeline.
     */
    fun openReport(privateBundle: ByteArray, ciphertext: ByteArray, trackerUserId: Long): LocationValue {
        val kemPriv = kemPrivFromPrivateBundle(privateBundle)
        val plain = Pqc.decrypt(kemPriv, ciphertext)
        val compat = json.decodeFromString<LocationValueCompatible>(plain.decodeToString())
        return compat.toLocationValue().copy(userid = trackerUserId)
    }

    /** Extracts the ML-KEM private DER from a `[4B kemPrivLen][kemPriv][dsaPriv]` bundle. */
    fun kemPrivFromPrivateBundle(priv: ByteArray): ByteArray {
        val len = ((priv[0].toInt() and 0xFF) shl 24) or
            ((priv[1].toInt() and 0xFF) shl 16) or
            ((priv[2].toInt() and 0xFF) shl 8) or
            (priv[3].toInt() and 0xFF)
        return priv.copyOfRange(KEM_LEN_PREFIX_LEN, KEM_LEN_PREFIX_LEN + len)
    }

    internal fun u64be(v: Long): ByteArray {
        val out = ByteArray(U64_LEN)
        for (i in 0 until U64_LEN) out[i] = (v ushr (U64_MSB_SHIFT - i * BITS_PER_BYTE)).toByte()
        return out
    }
}
