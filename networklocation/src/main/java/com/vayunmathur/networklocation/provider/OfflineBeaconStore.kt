package com.vayunmathur.networklocation.provider

import android.content.Context
import android.os.ParcelFileDescriptor
import com.vayunmathur.networklocation.BeaconFix
import com.vayunmathur.networklocation.BeaconId
import com.vayunmathur.networklocation.BeaconKeys
import com.vayunmathur.networklocation.OfflineDatabases
import com.vayunmathur.networklocation.WpsStoreNative

/**
 * Offline beacon → coordinate resolver over the two WPSDB stores
 * ([OfflineDatabases.WIFI], [OfflineDatabases.CELL]), read by [WpsStoreNative] (native Rust
 * reader) from device-protected storage. They are downloaded rather than bundled, the same as
 * the offline geocoder in `GeocodeService`.
 *
 * Degrades gracefully: if a store has not been downloaded or the native library did not
 * load, the corresponding handle stays 0 and every lookup misses, so no position is reported
 * and a DB-less dev build still works. There is no online fallback. A store that arrives
 * later is picked up on the next lookup ([reopenIfArrived]) rather than at the next process
 * start.
 *
 * Key packing lives in [BeaconKeys] and must stay identical to the builder in
 * `scripts/networklocation/wps_harvest`.
 */
class OfflineBeaconStore(context: Context) {
    private val appContext = context.applicationContext

    private var wifiPfd: ParcelFileDescriptor? = null
    private var cellPfd: ParcelFileDescriptor? = null
    private var wifiHandle = 0L
    private var cellHandle = 0L

    // Serializes lookups against close(): native close() frees the reader (Box::from_raw),
    // so it must not run while another thread is inside WpsStoreNative.lookup on that handle.
    private val lock = Any()

    init {
        open()
    }

    /**
     * (Re)open both stores. Call again once a download completes; existing handles are
     * released first, and a store that is still absent simply leaves its handle at 0.
     */
    fun open() {
        synchronized(lock) {
            closeLocked()
            if (!WpsStoreNative.available) return
            wifiHandle = openStore(OfflineDatabases.WIFI) { wifiPfd = it }
            cellHandle = openStore(OfflineDatabases.CELL) { cellPfd = it }
        }
    }

    private fun openStore(name: String, keep: (ParcelFileDescriptor) -> Unit): Long {
        val fd = OfflineDatabases.openReadOnly(appContext, name) ?: return 0L
        keep(fd)
        // Standalone file: the reader's base offset is 0, unlike the old APK-asset path.
        return WpsStoreNative.open(fd.fd, 0L, fd.statSize)
    }

    /**
     * Reopen both stores if either handle is still 0 and its file has since appeared.
     *
     * The stores are downloaded on demand and nothing signals completion, so without this a
     * provider bound at boot keeps missing until the process restarts. Costs one stat per
     * absent store on the lookup path.
     */
    private fun reopenIfArrived() {
        synchronized(lock) {
            val wifiArrived =
                wifiHandle == 0L && OfflineDatabases.isPresent(appContext, OfflineDatabases.WIFI)
            val cellArrived =
                cellHandle == 0L && OfflineDatabases.isPresent(appContext, OfflineDatabases.CELL)
            if (wifiArrived || cellArrived) open()
        }
    }

    /** Resolve WiFi APs present in the offline store. Absent MACs are simply omitted. */
    fun resolveWifi(bssids: List<BeaconId.Wifi>): Map<BeaconId.Wifi, BeaconFix> {
        if (bssids.isEmpty()) return emptyMap()
        reopenIfArrived()
        synchronized(lock) {
            if (wifiHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Wifi, BeaconFix>()
            for (id in bssids) {
                lookupWifi(id)?.let { out[id] = it }
            }
            return out
        }
    }

    private fun lookupWifi(id: BeaconId.Wifi): BeaconFix? {
        val key = BeaconKeys.parseMac(id.bssid) ?: return null
        if (BeaconKeys.isRandomizedMac(key)) return null
        val r = WpsStoreNative.lookup(wifiHandle, 0L, key) ?: return null
        if (r.size < MIN_LOOKUP_FIELDS) return null
        return BeaconFix(id, r[0], r[1], accuracyOf(r[2]))
    }

    /** Resolve cell towers present in the offline store. Absent towers are omitted. */
    fun resolveCell(cells: List<BeaconId.Cell>): Map<BeaconId.Cell, BeaconFix> {
        if (cells.isEmpty()) return emptyMap()
        reopenIfArrived()
        synchronized(lock) {
            if (cellHandle == 0L) return emptyMap()
            val out = HashMap<BeaconId.Cell, BeaconFix>()
            for (id in cells) {
                val r = WpsStoreNative.lookup(
                    cellHandle,
                    BeaconKeys.cellKeyHi(id),
                    BeaconKeys.cellKeyLo(id),
                ) ?: continue
                if (r.size >= MIN_LOOKUP_FIELDS) {
                    out[id] = BeaconFix(id, r[0], r[1], accuracyOf(r[2]))
                }
            }
            return out
        }
    }

    /** Which stores are open, for the fix loop's status line. */
    fun describe(): String = synchronized(lock) {
        "stores[wifi=${if (wifiHandle != 0L) "open" else "closed"} " +
            "cell=${if (cellHandle != 0L) "open" else "closed"} native=${WpsStoreNative.available}]"
    }

    /**
     * Release both handles and their descriptors. Idempotent. Serialized with the resolvers so
     * it never frees a native reader that a concurrent lookup is still dereferencing.
     */
    fun close() {
        synchronized(lock) {
            closeLocked()
        }
    }

    private fun closeLocked() {
        if (wifiHandle != 0L) {
            WpsStoreNative.close(wifiHandle)
            wifiHandle = 0L
        }
        if (cellHandle != 0L) {
            WpsStoreNative.close(cellHandle)
            cellHandle = 0L
        }
        wifiPfd?.close()
        wifiPfd = null
        cellPfd?.close()
        cellPfd = null
    }

    private companion object {
        /**
         * Radius used when the store holds a record but no accuracy for it (the source did
         * not report one, signalled by a negative value from the native reader).
         *
         * Deliberately pessimistic. The solver weights every beacon by the inverse of this —
         * see `six_sigma_squared` in `jni.rs` and the inlier test in `lib.rs` — so guessing
         * low would let an unmeasured beacon outvote measured ones.
         */
        const val UNKNOWN_ACCURACY_METERS = 100.0

        /** Native lookup fields: latitude, longitude, accuracy. */
        const val MIN_LOOKUP_FIELDS = 3

        fun accuracyOf(stored: Double): Double =
            if (stored < 0.0) UNKNOWN_ACCURACY_METERS else stored
    }
}
