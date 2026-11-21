package com.vayunmathur.findfamily.platform
import com.vayunmathur.findfamily.network.Networking
import com.vayunmathur.findfamily.domain.TrackerSighting

import android.os.BatteryManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.sync.PoweredOffReporting
import com.vayunmathur.findfamily.platform.PoweredOffScanner
import com.vayunmathur.findfamily.platform.TrackerBeaconScanner
import com.vayunmathur.findfamily.sync.TrackerReporting
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Logged when a sighting report is accepted but resolves to nothing reportable. */
private const val REPORT_SIGHTING_UNRESOLVED =
    "reportSighting returned false (epoch id unresolved or socket down)"

/**
 * Finder path: subscribe to tracker beacon sightings and, for each, upload a
 * report stamped with this device's current GPS (if accurate enough). The sealed
 * report is readable only by the tracker's owner. Started once from startTracking.
 */
internal fun LocationTrackingService.startTrackerScanner() {
    if (trackerScanJob?.isActive == true) return
    trackerScanJob = serviceScope.launch {
        runCatching {
            TrackerBeaconScanner(this@startTrackerScanner).sightings().collect { sighting ->
                val loc = lastKnownLocation
                if (loc == null) {
                    // Both of these drops used to be silent, which made a stalled
                    // crowd-finding pipeline indistinguishable from one that was
                    // never hearing the beacon at all.
                    Log.status("FF-Tracker", "sighting dropped: no location fix yet")
                    return@collect
                }
                if (loc.accuracy > MAX_FIX_ACCURACY_METERS) {
                    Log.status(
                        "FF-Tracker",
                        "sighting dropped: accuracy ${loc.accuracy}m > ${MAX_FIX_ACCURACY_METERS}m"
                    )
                    return@collect
                }
                val battery = runCatching {
                    bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
                }.getOrDefault(0f)
                val lv = LocationValue(
                    Networking.userid,
                    Coord(loc.latitude, loc.longitude),
                    0f,
                    loc.accuracy,
                    Clock.System.now(),
                    battery,
                )
                runCatching { TrackerReporting.reportSighting(sighting, lv) }
                    .onSuccess { if (!it) Log.status("FF-Tracker", REPORT_SIGHTING_UNRESOLVED) }
                    .onFailure { Log.status("FF-Tracker", "reportSighting failed", it) }
            }
        }.onFailure { Log.status("FF-Tracker", "tracker scan collect failed", it) }
    }
}

/**
 * Owner path: (re)register owned trackers so finders can resolve them, then fetch
 * and decrypt recent crowd reports and feed them through the normal incoming
 * pipeline so each tracker shows up as a map pin. Runs on the heartbeat tick.
 */
internal suspend fun LocationTrackingService.pollTrackerReports() {
    val store = trackerStore ?: return
    val trackers = runCatching { repository.getAllUsers().filter { it.kind == UserKind.TRACKER } }
        .getOrDefault(emptyList())
    if (trackers.isEmpty()) return
    val locs = ArrayList<LocationValue>()
    for (t in trackers) {
        runCatching { TrackerReporting.registerTracker(t, store) }
        locs += runCatching { TrackerReporting.fetchTrackerLocations(t, store) }
            .getOrDefault(emptyList())
    }
    if (locs.isNotEmpty()) {
        // Tracker crowd reports are anonymous finder sightings restamped locally — no relay
        // sender, no pair ID. processIncomingLocations clamps reportedAt at
        // persist time: finder-set timestamps flow through the same
        // getLatest() MAX(reportedAt) ranking, so no pre-filter is needed here.
        processIncomingLocations(locs)
    }
}

/**
 * Finder path for powered-off devices. Unlike the tracker scanner above this is **not**
 * DEV_BUILD gated and needs no privileged permission — the whole point is that any phone
 * with findfamily on it can contribute sightings. It is gated on the user having opted in.
 *
 * Collecting [LocationServiceController.crowdFindingEnabledFlow] rather than reading the
 * flag once means flipping the switch off actually stops the radio, instead of leaving it
 * scanning until the service happens to restart.
 */
internal fun LocationTrackingService.startPoweredOffScanner() {
    if (poweredOffScanJob?.isActive == true) return
    poweredOffScanJob = serviceScope.launch {
        LocationServiceController.crowdFindingEnabledFlow(this@startPoweredOffScanner)
            .collectLatest { enabled ->
                if (!enabled) return@collectLatest
                runCatching {
                    PoweredOffScanner(this@startPoweredOffScanner).sightings().collect { sighting ->
                        val loc = lastKnownLocation
                        if (loc == null) {
                            Log.status(LocationTrackingService.TAG_POWERED_OFF, "sighting dropped: no location fix yet")
                            return@collect
                        }
                        // A sighting is only ever "the finder was near here". Reporting one
                        // from a coarse fix would add noise the owner cannot tell
                        // apart from a good one, so drop it rather than dilute the answer.
                        if (loc.accuracy > MAX_FIX_ACCURACY_METERS) {
                            Log.status(
                                LocationTrackingService.TAG_POWERED_OFF,
                                "sighting dropped: accuracy ${loc.accuracy}m > ${MAX_FIX_ACCURACY_METERS}m"
                            )
                            return@collect
                        }
                        val lv = LocationValue(
                            Networking.userid,
                            Coord(loc.latitude, loc.longitude),
                            0f,
                            loc.accuracy,
                            Clock.System.now(),
                            // The finder's own battery is none of the owner's business, and
                            // sending it would leak a little about who did the finding.
                            0f,
                        )
                        runCatching { PoweredOffReporting.reportSighting(sighting, lv) }
                            .onFailure {
                                Log.status(LocationTrackingService.TAG_POWERED_OFF, "reportSighting failed", it)
                            }
                    }
                }.onFailure {
                    Log.status(LocationTrackingService.TAG_POWERED_OFF, "powered-off scan collect failed", it)
                }
            }
    }
}

/**
 * Owner path for powered-off devices: for every peer whose powered-off keys we hold, drain
 * and decrypt any sightings and feed them through the normal incoming pipeline. Finding
 * nothing is the ordinary case and is not worth logging at anything above debug.
 *
 * Rate-limited to [LocationTrackingService.POWERED_OFF_POLL_INTERVAL_MS] rather than running on the 30s heartbeat.
 * A query carries one handle per armed slot — 258 of them, about 4KB — and an EID only
 * rotates every 1024s, so polling every 30s would send that 34 times before there could
 * possibly be a new handle to ask about.
 */
internal suspend fun LocationTrackingService.pollPoweredOffSightings() {
    val store = poweredOffKeys ?: return
    val now = System.currentTimeMillis()
    if (now - lastPoweredOffPollMs < LocationTrackingService.POWERED_OFF_POLL_INTERVAL_MS) return
    lastPoweredOffPollMs = now
    val users = runCatching { repository.getAllUsers() }.getOrDefault(emptyList())
    val locs = ArrayList<LocationValue>()
    for (u in users) {
        if (!store.canRead(u.id)) continue
        locs += runCatching { PoweredOffReporting.fetchSightings(u.id, store) }
            .getOrDefault(emptyList())
    }
    if (locs.isNotEmpty()) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "retrieved ${locs.size} network sighting(s)")
        // Powered-off sightings are anonymous finder reports restamped locally — no relay
        // sender, no pair ID. processIncomingLocations clamps reportedAt at
        // persist time (same getLatest() ranking).
        processIncomingLocations(locs)
    }
}
