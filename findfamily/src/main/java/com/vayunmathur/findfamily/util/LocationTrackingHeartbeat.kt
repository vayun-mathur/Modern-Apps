package com.vayunmathur.findfamily.util

import android.location.Location
import android.os.BatteryManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.DirectBootStore
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.domain.NoShowPolicy
import com.vayunmathur.findfamily.R
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How stale the held fix has to be before a less accurate one replaces it.
 *
 * Long enough that a burst of coarse network fixes cannot displace a good GPS one,
 * short enough that a device which has lost GPS still reports where it now is.
 */
private val FIX_MAX_AGE_NANOS = 2.minutes.inWholeNanoseconds

/**
 * Fixes worse than this are never held, published, or used for crowd reports.
 *
 * Applies at intake ([recordFix]) and to inbound peer fixes before they reach
 * Room. Shutdown and low-battery parting reports bypass it: they deliberately
 * republish the last held fix however stale, so the last-seen stays truthful.
 *
 * Delegates to [NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS] so the fix pipeline,
 * the live geofence, and the no-show path can never drift apart.
 */
internal val MAX_FIX_ACCURACY_METERS: Float
    get() = NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS.toFloat()

/**
 * Keeps the best recent fix rather than simply the newest one.
 *
 * The network provider delivers a fix every ten seconds and its answer wanders by tens
 * to hundreds of metres between them, so overwriting a recent GPS fix with one of those
 * made a phone sitting on a table appear to move around the city. A coarser fix only
 * wins once the one being held has gone stale enough to be the worse answer.
 *
 * Fixes worse than [MAX_FIX_ACCURACY_METERS] are dropped here and never become
 * [LocationTrackingService.lastKnownLocation].
 */
private fun Location.hasUsableAccuracy(): Boolean {
    if (!hasAccuracy()) return false
    if (!accuracy.isFinite()) return false
    if (accuracy < 0f) return false
    return accuracy <= MAX_FIX_ACCURACY_METERS
}

internal fun LocationTrackingService.recordFix(location: Location) {
    if (!location.hasUsableAccuracy()) {
        return
    }
    val held = lastKnownLocation
    if (held == null) {
        lastKnownLocation = location
        return
    }
    val age = location.elapsedRealtimeNanos - held.elapsedRealtimeNanos
    if (age <= 0) return
    if (age > FIX_MAX_AGE_NANOS || location.accuracy <= held.accuracy) {
        lastKnownLocation = location
    }
}

private fun LocationTrackingService.logHeartbeatSummary(
    location: Location,
    userCount: Int,
    linkCount: Int,
) {
    val userId = Networking.userid
    val loc = "${location.latitude},${location.longitude} acc=${location.accuracy}"
    Log.dev("FF-Heartbeat", "heartbeat userid=${userId.toULong()} self raw=$userId users=$userCount")
    Log.dev("FF-Heartbeat", "heartbeat links=$linkCount moving=$isMoving loc=$loc")
}

private suspend fun LocationTrackingService.upsertSelfLocation(
    location: Location,
    now: Instant,
): LocationValue {
    val locationValue = LocationValue(
        Networking.userid,
        Coord(location.latitude, location.longitude),
        // Carry the fix's own speed: hardcoding 0 here made every peer's
        // card read 0 forever, however fast they were actually moving.
        if (location.hasSpeed()) location.speed else 0f,
        location.accuracy,
        now,
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    )
    Log.debug("FF-Heartbeat", "upsert local LocationValue for self")
    repository.upsertLocation(locationValue)
    return locationValue
}

private suspend fun LocationTrackingService.ensureSelfUser(currentUsers: List<User>) {
    if (currentUsers.none { it.id == Networking.userid }) {
        Log.debug("FF-Heartbeat", "self not in user DB, inserting me")
        repository.upsertUser(
            User(
                getString(R.string.me_label),
                null,
                "Unnamed Location",
                true,
                RequestStatus.MUTUAL_CONNECTION,
                Clock.System.now(),
                null,
                Networking.userid
            )
        )
    }
}

// Broad catch is deliberate: auto-toggle DAO failures must not kill the heartbeat tick.
@Suppress("TooGenericExceptionCaught")
private suspend fun LocationTrackingService.applyTimerAutoToggles(
    currentUsers: List<User>,
    now: Instant,
): List<User> {
    // Auto-toggle check: atomic flip guarded by the timer value itself.
    // If the user manually cleared or rescheduled after we read currentUsers, the
    // WHERE clause (sharingAutoToggleAt <= now) won't match and we won't accidentally
    // disable/enable when they didn't intend it. No stale copy() + upsert().
    try {
        val flipped = repository.applyDueAutoToggles(now.epochSeconds)
        if (flipped > 0) {
            Log.dev("FF-Heartbeat", "auto-toggle flipped $flipped user(s), reloading sharing state")
            // Reload fresh sharing flags so we don't publish once after an intended disable,
            // and we start publishing immediately after an intended enable.
            return repository.getAllUsers()
        }
    } catch (e: Exception) {
        Log.status("FF-Heartbeat", "auto-toggle apply failed", e)
    }
    return currentUsers
}

// Broad catch is deliberate: waypoint DAO failures must not kill the heartbeat tick.
@Suppress("TooGenericExceptionCaught")
private suspend fun LocationTrackingService.applyArrivalAutoToggles(
    publishBaseUsers: List<User>,
    location: Location,
): List<User> {
    // Arrival auto-toggle check (GitHub #406): flip sharing for any user whose trigger
    // points at a saved place that "Me" is currently inside. Uses the same atomic,
    // waypoint-id-guarded update as the timer path so a stale snapshot cannot mis-flip.
    try {
        val myCoord = Coord(location.latitude, location.longitude)
        val insideWaypointIds = repository.getAllWaypoints()
            .filter { havershine(it.coord, myCoord) < it.range }
            .map { it.id }
        if (insideWaypointIds.isNotEmpty()) {
            val flippedArrival = repository.applyDueArrivalToggles(insideWaypointIds)
            if (flippedArrival > 0) {
                Log.dev("FF-Heartbeat", "arrival auto-toggle flipped $flippedArrival user(s), reloading")
                return repository.getAllUsers()
            }
        }
    } catch (e: Exception) {
        Log.status("FF-Heartbeat", "arrival auto-toggle apply failed", e)
    }
    return publishBaseUsers
}

private suspend fun LocationTrackingService.publishHeartbeat(
    locationValue: LocationValue,
    publishBaseUsers: List<User>,
    currentLinks: List<TemporaryLink>,
    now: Instant,
) {
    // The sharing tile (GitHub #648) suppresses outbound publishing without touching the
    // per-person switches, so turning it back on resumes exactly the set of people the
    // user was sharing with. Receiving, waypoints and tracker reporting are unaffected.
    val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
    val publishTargets = if (!sharingOut) emptyList<User>()
    else publishBaseUsers.filter { it.id != Networking.userid && it.sendingEnabled }
    val targetIds = publishTargets.map { it.id.toULong() }
    val targetNames = publishTargets.map { it.name }
    Log.dev("FF-Heartbeat", "publish targets count=${publishTargets.size} ids=$targetIds names=$targetNames")
    Log.dev("FF-Heartbeat", "publish globalSharing=$sharingOut")
    publishTargets.forEach {
        val result = runCatching { Networking.publishLocation(locationValue, it) }
        if (result.isFailure) {
            Log.status("FF-Heartbeat", "publish to ${it.id.toULong()} threw", result.exceptionOrNull())
        }
    }
    if (sharingOut) currentLinks.filter { now < it.deleteAt }.forEach {
        val result = runCatching { Networking.publishLocation(locationValue, it) }
        if (result.isFailure) {
            Log.status("FF-Heartbeat", "publish to link ${it.id} threw", result.exceptionOrNull())
        }
    }
    currentLinks.filter { now >= it.deleteAt }.forEach {
        runCatching { repository.temporaryLinkStore.delete(it) }
    }
}

// Broad catch is deliberate: one failing DAO/crypto/network call must not kill the foreground service loop.
@Suppress("TooGenericExceptionCaught")
internal suspend fun LocationTrackingService.syncHeartbeat() {
    val location = lastKnownLocation ?: run {
        Log.debug("FF-Heartbeat", "syncHeartbeat: no lastKnownLocation yet")
        return
    }
    if (Networking.userid == 0L) {
        Log.debug("FF-Heartbeat", "syncHeartbeat: userid==0, not initialized yet")
        return
    }

    // Shield the entire heartbeat so one failing DAO / crypto / network call
    // does not kill the foreground service loop (which previously surfaced as
    // FATAL BadPaddingException in decrypt).
    try {
        val currentUsers = repository.getAllUsers()
        val currentLinks = repository.temporaryLinkStore.getAll()
        val now = Clock.System.now()
        logHeartbeatSummary(location, currentUsers.size, currentLinks.size)
        val locationValue = upsertSelfLocation(location, now)
        ensureSelfUser(currentUsers)
        var publishBaseUsers = applyTimerAutoToggles(currentUsers, now)
        publishBaseUsers = applyArrivalAutoToggles(publishBaseUsers, location)
        publishHeartbeat(locationValue, publishBaseUsers, currentLinks, now)
        // Incoming peer locations arrive via the live WebSocket push (see startTracking →
        // Networking.startLive). There is no HTTP receive; if the socket is down the loop
        // reconnects and the next heartbeat re-publishes.
    } catch (e: Exception) {
        Log.status("FF-Heartbeat", "syncHeartbeat crashed", e)
    }
}

/**
 * Mirror the identity, switches and sharing roster into device-protected storage so the
 * next reboot can report before the passcode is entered.
 *
 * Reads its own state rather than borrowing the heartbeat's, because the heartbeat gives
 * up early when there is no fix yet — and a device that has never had a fix still needs a
 * seeded mirror. Rewritten only when the result would differ, since this runs every tick.
 */
internal suspend fun LocationTrackingService.seedDirectBootMirror() {
    val sharingOut = LocationServiceController.isGlobalSharingEnabled(this)
    val trackingEnabled = LocationServiceController.isTrackingEnabled(this)
    val targets = repository.getAllUsers()
        .filter { it.id != Networking.userid && it.sendingEnabled }
        .mapNotNull { u -> u.pqcEncryptionKey?.let { DirectBootStore.Target(u.id, it, u.outboundPairId) } }
    val signature = "$sharingOut|$trackingEnabled|" + targets.joinToString(",") { "${it.id}:${it.bundle.length}:${it.pairId?.take(4)}" }
    publishRoster = if (sharingOut) targets else emptyList()
    if (signature == lastSeededMirror) return
    DirectBootStore.seed(
        this,
        targets,
        trackingEnabled = trackingEnabled,
        globalSharingEnabled = sharingOut,
    )
    lastSeededMirror = signature
    Log.status(
        LocationTrackingService.TAG_DIRECT_BOOT,
        "mirror seeded: ${targets.size} target(s) sharing=$sharingOut tracking=$trackingEnabled"
    )
}
