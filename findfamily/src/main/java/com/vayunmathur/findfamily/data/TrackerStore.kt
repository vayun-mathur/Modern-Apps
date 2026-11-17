package com.vayunmathur.findfamily.data

import com.vayunmathur.library.util.DataStoreUtils

/**
 * Owner-only persistence for bound trackers. Holds, per tracker userid, the two
 * secrets that never leave the owner's device:
 *  - the beacon master secret (used to recompute rotating epoch-ids), and
 *  - the tracker's ML-KEM **private** bundle (used to decrypt crowd reports).
 *
 * The tracker's **public** bundle is stored on the `User` row itself
 * (`User.pqcEncryptionKey`), reusing the existing key-directory plumbing. The set
 * of owned trackers is simply the `User` rows with `kind == UserKind.TRACKER`, so
 * no separate index is kept here.
 *
 * The tracker's BLE address is kept here too. It isn't secret, but the owner needs it
 * to re-open GATT for a UWB find ([TrackerUwbGatt.startRanging]) — the crowd-finding
 * path only ever scans by service UUID, so nothing else would retain it.
 */
class TrackerStore(private val ds: DataStoreUtils) {

    suspend fun save(trackerUserId: Long, secret: ByteArray, privateBundle: ByteArray, bleAddress: String? = null) {
        ds.setByteArray(secretKey(trackerUserId), secret)
        ds.setByteArray(privKey(trackerUserId), privateBundle)
        if (bleAddress != null) ds.setString(macKey(trackerUserId), bleAddress)
    }

    suspend fun secret(trackerUserId: Long): ByteArray? = ds.getByteArrayAwait(secretKey(trackerUserId))

    suspend fun privateBundle(trackerUserId: Long): ByteArray? = ds.getByteArrayAwait(privKey(trackerUserId))

    /** The BLE address this tracker was bound over, or null if it was bound before this was stored. */
    suspend fun bleAddress(trackerUserId: Long): String? = ds.getStringAwait(macKey(trackerUserId))

    private fun secretKey(id: Long) = "ff_tracker_secret_$id"
    private fun privKey(id: Long) = "ff_tracker_priv_$id"
    private fun macKey(id: Long) = "ff_tracker_mac_$id"
}
