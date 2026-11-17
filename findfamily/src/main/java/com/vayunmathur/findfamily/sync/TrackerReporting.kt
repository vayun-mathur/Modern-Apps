package com.vayunmathur.findfamily.sync
import com.vayunmathur.findfamily.domain.TrackerProtocol
import com.vayunmathur.findfamily.domain.TrackerSighting
import com.vayunmathur.findfamily.data.TrackerStore

import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.network.fetchTrackerReports
import com.vayunmathur.findfamily.network.registerTracker
import com.vayunmathur.findfamily.network.resolveTrackerBundle
import com.vayunmathur.findfamily.network.uploadTrackerReport
import kotlin.io.encoding.Base64

/**
 * Ties the tracker crypto ([TrackerProtocol]) to the relay ([Networking]) for both
 * roles of the crowd-finding network.
 */
object TrackerReporting {
    private const val TAG = "TrackerReporting"

    /**
     * Owner: (re)register a bound tracker with the server so finders can resolve its
     * rotating beacon ids to its ML-KEM bundle. Idempotent — safe to call on every
     * socket (re)connect. The public bundle lives on the `User` row
     * ([User.pqcEncryptionKey]); the beacon secret lives in [TrackerStore].
     */
    suspend fun registerTracker(tracker: User, store: TrackerStore): Boolean {
        if (tracker.kind != UserKind.TRACKER) return false
        val secret = store.secret(tracker.id) ?: return false
        val bundleB64 = tracker.pqcEncryptionKey ?: return false
        val bundle = runCatching { Base64.decode(bundleB64) }.getOrNull() ?: return false
        return Networking.registerTracker(tracker.id, secret, bundle)
    }

    /**
     * Finder: resolve a sighting's rotating id to the owning tracker's ML-KEM bundle,
     * seal this finder's current [finderLocation] to it, and upload the report keyed
     * by the beacon id. Returns false when the id can't be resolved (unknown/foreign
     * tracker) or the socket is down.
     */
    suspend fun reportSighting(sighting: TrackerSighting, finderLocation: LocationValue): Boolean {
        val bundle = Networking.resolveTrackerBundle(sighting.epochId) ?: return false
        val ct = TrackerProtocol.sealReport(bundle, finderLocation)
        val ok = Networking.uploadTrackerReport(sighting.epochId, ct)
        if (ok) Log.status(TAG, "uploaded sighting (rssi=${sighting.rssi}, battery=${sighting.battery})")
        return ok
    }

    /**
     * Owner: fetch and decrypt recent crowd reports for a single [tracker]. Each
     * decrypted [LocationValue] is stamped with the tracker's userid so it flows
     * through the normal incoming-location pipeline and renders as that tracker's pin.
     */
    suspend fun fetchTrackerLocations(tracker: User, store: TrackerStore): List<LocationValue> {
        if (tracker.kind != UserKind.TRACKER) return emptyList()
        val secret = store.secret(tracker.id) ?: return emptyList()
        val priv = store.privateBundle(tracker.id) ?: return emptyList()
        val ids = TrackerProtocol.recentEpochIds(secret)
        val cts = Networking.fetchTrackerReports(ids)
        if (cts.isEmpty()) return emptyList()
        return cts.mapNotNull { ct ->
            runCatching { TrackerProtocol.openReport(priv, ct, tracker.id) }
                .onFailure { Log.status(TAG, "openReport failed for tracker ${tracker.id}", it) }
                .getOrNull()
        }
    }
}
