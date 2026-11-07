package com.vayunmathur.findfamily.tracker

import com.vayunmathur.library.log.Log
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.util.fetchTrackerReports
import com.vayunmathur.findfamily.util.resolveTrackerBundle
import com.vayunmathur.findfamily.util.uploadTrackerReport
import kotlinx.serialization.json.Json

/**
 * Ties powered-off sightings to the relay, for both roles.
 *
 * ## The privacy property, stated exactly
 * A finder sees an opaque rotating [PoweredOffProtocol.eid], asks the relay for the ML-KEM-768
 * *public* bundle it belongs to, seals **its own** GPS position to that bundle, and uploads the
 * ciphertext. So:
 *  - the **finder** learns a random-looking 20 bytes and a public key. Not an identity, not a
 *    name, not where the beaconing device is, not even that it is a phone. Its own userid is
 *    stripped from the sealed payload, so the owner cannot tell who found their device either.
 *  - the **relay** stores bytes it has no key for.
 *  - only the **owner**, or a peer they deliberately shared with, can open it.
 *
 * What is *not* true, and should not be claimed: the relay does learn "handle X was sighted",
 * and since it holds the registration it can tie that to a device. It does not learn where — the
 * position is inside the ciphertext. That linkability is inherent to the existing crowd design
 * rather than something added here, but it is real.
 */
object PoweredOffReporting {
    private const val TAG = "PoweredOffReporting"

    /**
     * EIDs already reported, so a beacon two metres away does not cost a resolve and an upload
     * every two seconds. Reporting each EID once is the natural rate: the EID rotates every
     * [PoweredOffProtocol.SLOT_SECONDS], so a beacon we sit next to all day still produces a
     * steady trickle of fresh sightings rather than nothing.
     */
    private val reportedEids = LinkedHashMap<String, Long>()
    private const val MAX_REMEMBERED_EIDS = 512

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Finder: resolve, seal this finder's [finderLocation] to the beacon's public bundle, and
     * upload. Returns false when the handle resolves to nothing (a beacon from a device we have
     * no business reporting, or a relay that predates this feature) or the socket is down.
     */
    suspend fun reportSighting(sighting: PoweredOffSighting, finderLocation: LocationValue): Boolean {
        if (isRecentlyReported(sighting.eid)) return false
        val handle = PoweredOffProtocol.handleOf(sighting.eid)
        val bundle = Networking.resolveTrackerBundle(handle) ?: return false
        val ct = TrackerProtocol.sealReport(bundle, finderLocation)
        val ok = Networking.uploadTrackerReport(handle, ct)
        // Only a successful upload earns the dedup slot. Marking before the
        // attempt (the old behaviour) meant one transient failure - socket
        // mid-reconnect, relay unreachable - suppressed every retry for the
        // EID's whole 1024-second slot, and on all-matches scanners that was
        // the difference between a delayed sighting and a lost one.
        if (ok) {
            markReported(sighting.eid)
            Log.status(TAG, "uploaded powered-off sighting (rssi=${sighting.rssi})")
        }
        return ok
    }

    /**
     * Owner: fetch and decrypt sightings of [userId]'s powered-off beacon, newest first, or an
     * empty list if there are none — which is the ordinary case and must not be dressed up as
     * anything else. Each result is stamped [LocationSource.NETWORK_SIGHTING] here rather than
     * trusting the finder to have set it, so a sighting can never be rendered as a live fix.
     */
    suspend fun fetchSightings(userId: Long, store: PoweredOffKeyStore): List<LocationValue> {
        val secret = store.secret(userId) ?: return emptyList()
        val kemPriv = store.kemPrivateKey(userId) ?: return emptyList()
        val cts = Networking.fetchTrackerReports(PoweredOffProtocol.recentHandles(secret))
        if (cts.isEmpty()) return emptyList()
        return cts.mapNotNull { ct ->
            runCatching { openSighting(kemPriv, ct, userId) }
                .onFailure { Log.status(TAG, "could not open a sighting for $userId", it) }
                .getOrNull()
        }.sortedByDescending { it.timestamp }
    }

    /**
     * Decrypt one sealed sighting.
     *
     * Deliberately **not** [TrackerProtocol.openReport], even though the envelope is identical.
     * That one takes a tracker's composite `[4B kemPrivLen][kemPriv][dsaPriv]` bundle, because a
     * tracker's identity is minted by the phone on its behalf. A powered-off phone is not a
     * tracker: it reuses its own existing ML-KEM identity, and what the beacon half files is the
     * **raw** private DER straight out of the direct-boot identity mirror (`ff_pqcKemPriv`, one of
     * the four separate `ff_pqc*` blobs). Passing that to the bundle parser makes it read the
     * DER's leading SEQUENCE header as a length field, and every decrypt fails.
     *
     * The sealing side needs no equivalent care: finders seal with `Pqc.encryptTo`, which takes a
     * *public* bundle, and the relay hands back this user's ordinary public bundle, which is
     * already in that shape.
     */
    private fun openSighting(kemPriv: ByteArray, ciphertext: ByteArray, userId: Long): LocationValue {
        val plain = Pqc.decrypt(kemPriv, ciphertext)
        return json.decodeFromString<LocationValueCompatible>(plain.decodeToString())
            .toLocationValue()
            .copy(userid = userId, source = LocationSource.NETWORK_SIGHTING)
    }

    /** True if [eid] was already successfully reported within the last two slots. */
    @Synchronized
    internal fun isRecentlyReported(eid: ByteArray): Boolean {
        val hex = eid.joinToString("") { "%02x".format(it) }
        val staleBefore = System.currentTimeMillis() - PoweredOffProtocol.SLOT_SECONDS * 2000L
        reportedEids.entries.removeAll { it.value < staleBefore }
        return reportedEids.containsKey(hex)
    }

    /** Remember a successful report of [eid] so repeats within its slot are skipped. */
    @Synchronized
    internal fun markReported(eid: ByteArray) {
        val hex = eid.joinToString("") { "%02x".format(it) }
        reportedEids[hex] = System.currentTimeMillis()
        while (reportedEids.size > MAX_REMEMBERED_EIDS) {
            reportedEids.remove(reportedEids.keys.first())
        }
    }
}
