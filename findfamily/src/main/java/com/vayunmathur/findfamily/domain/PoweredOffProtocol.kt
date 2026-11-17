package com.vayunmathur.findfamily.domain

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Shared contract for powered-off finding. **Both halves of the feature derive from this
 * object** — the beacon half arms the Bluetooth controller with [eids] before shutdown, the
 * finder half matches what it hears and the owner half looks reports up by [handleOf]. If the
 * two halves ever disagree the feature fails completely silently: no error, no log, just zero
 * sightings forever. Change nothing here without changing both sides.
 *
 * ## Why this is separate from [TrackerProtocol]
 * Powered-off beacons are emitted by the Bluetooth *controller* after Android is gone, so the
 * shape is dictated by the vendor HCI interface rather than by us
 * (`hardware/interfaces/bluetooth/bluetooth_hal/extensions/finder/`). Two constants there are
 * not negotiable and neither matches the UWB tracker network:
 *  - `Eid.aidl` is literally `byte[20] bytes` and the vendor command copies exactly 20 bytes
 *    per key, so [EID_LEN] is 20, not [TrackerProtocol.EPOCH_ID_LEN].
 *  - the HAL hardcodes `kPrecomputedKeyRotatedInterval = 0x400`, so [SLOT_SECONDS] is 1024, not
 *    [TrackerProtocol.EPOCH_SECONDS]. Deriving on the tracker's 900s period would drift 124s per
 *    slot against the controller's own timer and desynchronise within a couple of hours.
 * A distinct [EID_DOMAIN] keeps the two id spaces from ever colliding.
 *
 * ## Slot anchoring — the subtle part
 * The controller starts key index 0 shortly after shutdown and advances every [SLOT_SECONDS] on
 * its own timer, so its schedule is anchored to *shutdown time*, not to the wall clock. The
 * beacon half still derives on wall-clock slots and arms key 0 = the slot containing the
 * shutdown instant. The consequence is that a key is emitted slightly *later* than the slot it
 * was derived for, by an amount strictly less than one slot that does not accumulate. That
 * bounded lag is the whole reason the owner queries [recentHandles] backwards in time rather
 * than for a single slot. A shutdown-anchored counter would not be derivable by anyone who does
 * not already know the shutdown timestamp, which is why we do not use one.
 *
 * ## The EID is not a public key
 * The AOSP javadoc describes Find My Device network EIDs as "the public part of asymmetric key
 * pairs" that finders encrypt to directly. That is not implementable here: 20 bytes cannot hold
 * an X25519 or ML-KEM-768 public key, and Google only manages it by using secp160r1, which
 * Conscrypt does not support. So an EID here is an opaque rotating *handle*; the actual
 * encryption target is the device's ML-KEM-768 public bundle, which the finder fetches by
 * handle. See [PoweredOffReporting] for what that costs in privacy terms.
 */
object PoweredOffProtocol {

    /** Length of one EID. Fixed at 20 by `Eid.aidl` and the vendor HCI command. */
    const val EID_LEN: Int = 20

    /** EID rotation period. Fixed at 1024s by `kPrecomputedKeyRotatedInterval` in the HAL. */
    const val SLOT_SECONDS: Long = 1024L

    /**
     * How many slots the beacon half arms, and therefore how many the owner half queries.
     *
     * 256 is the hard ceiling and there is no reason to arm fewer: the controller's key index is
     * a single byte on the wire (`command[5]` and `command[10]` in
     * `bluetooth_finder_handler.cc`), so 0..255 is everything it can address. At
     * [SLOT_SECONDS] each that is 72.8 hours — a little over three days of powered-off
     * advertising, which is the real limit on how long a switched-off phone stays findable.
     *
     * What the controller does when the list runs out is **not specified anywhere we can read**.
     * The likeliest behaviour by a distance is that it stops advertising, since a beacon that
     * degenerated into a static identifier after three days would trip the unwanted-tracking
     * standard Google co-authored. That is inference from the fact that Google ships this same
     * HAL on retail hardware, not something confirmed from source, and it should keep being
     * described that way.
     */
    const val ARMED_SLOTS: Int = 256

    /** Domain-separation tag. Deliberately not `TrackerProtocol`'s, so the id spaces cannot collide. */
    private const val EID_DOMAIN = "ffpof1"

    /** Milliseconds per second, for wall-clock to slot conversion. */
    private const val MILLIS_PER_SECOND = 1000L

    /** The wall-clock slot number for a time in milliseconds. */
    fun currentSlot(nowMs: Long = System.currentTimeMillis()): Long =
        (nowMs / MILLIS_PER_SECOND) / SLOT_SECONDS

    /**
     * The 20-byte EID for a `(secret, slot)` pair:
     * `HMAC-SHA256(secret, "ffpof1" || u64_be(slot))[..20]`. The secret is the same per-device
     * [TrackerProtocol.SECRET_LEN]-byte value the rest of the crowd network uses.
     */
    fun eid(secret: ByteArray, slot: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        mac.update(EID_DOMAIN.toByteArray(Charsets.US_ASCII))
        mac.update(TrackerProtocol.u64be(slot))
        return mac.doFinal().copyOf(EID_LEN)
    }

    /**
     * The EIDs to arm the controller with when shutting down at [nowMs], oldest first. Index 0
     * is the slot containing the shutdown instant, which is the key the controller starts on.
     */
    fun armingEids(
        secret: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        count: Int = ARMED_SLOTS,
    ): List<ByteArray> {
        val first = currentSlot(nowMs)
        return (0 until count).map { eid(secret, first + it) }
    }

    /**
     * The lookup handle for an EID: its first 16 bytes.
     *
     * The relay keys resolutions and report buckets on 16 bytes
     * (`[0x07][16B]`, `[0x09][16B][ct]`), and there is no reason to widen a wire format that
     * already works. Truncating a 20-byte HMAC output to 16 leaves it just as unpredictable, and
     * a collision between two devices' handles would only cause a wasted resolve.
     */
    fun handleOf(eid: ByteArray): ByteArray = eid.copyOf(TrackerProtocol.EPOCH_ID_LEN)

    /**
     * Handles the owner should query to collect sightings from a powered-off window that started
     * up to [back] slots ago, newest first.
     *
     * Reaches one slot into the future as well, because the beacon may have been armed by a
     * device whose clock ran slightly ahead of this one; the extra query is free.
     */
    fun recentHandles(
        secret: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
        back: Int = ARMED_SLOTS,
    ): List<ByteArray> {
        val cur = currentSlot(nowMs)
        return (-1..back).map { handleOf(eid(secret, cur - it)) }
    }
}
