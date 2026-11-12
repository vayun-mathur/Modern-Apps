package com.vayunmathur.findfamily.util

import com.vayunmathur.library.log.Log
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.LocationSource
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.data.havershine
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.domain.NoShowPolicy
import com.vayunmathur.findfamily.domain.PairAuth
import com.vayunmathur.findfamily.uwb.UwbCancelReason
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.findfamily.uwb.UwbEnvelopeKind
import com.vayunmathur.findfamily.uwb.UwbInbox
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.tracker.PoweredOffKeyStore
import com.vayunmathur.findfamily.tracker.poweredOffGrantSigningBytes
import com.vayunmathur.findfamily.uwb.PoweredOffGrant
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/**
 * Accuracy beyond which a fix is not used to decide geofence membership.
 * Delegates to [NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS] so the live path and
 * the no-show path can never drift apart.
 */
internal val GEOFENCE_MAX_ACCURACY_METERS: Double
    get() = NoShowPolicy.NO_SHOW_MAX_ACCURACY_METERS

/** How far past a geofence's radius a fix has to be before it counts as having left. */
internal const val WAYPOINT_EXIT_HYSTERESIS = 1.2

/** Battery level at or below which a low-battery alert fires once per crossing. */
private const val LOW_BATTERY_THRESHOLD = 15f

/** Assumed battery when no prior fix exists to compare against. */
private const val FULL_BATTERY = 100f

private const val TAG_UWB = "FF-Uwb"

/** Sends one UWB envelope to a peer; shaped like [Networking.publishUwbMessage] so tests can swap it. */
internal typealias UwbEnvelopeSender =
    suspend (envelope: UwbEnvelope, recipientUserId: Long, recipient: User?) -> Boolean

/**
 * Authenticated entry point for every inbound fix carrying a claimed sender.
 *
 * Three gates, in order — time-clamp, sender-auth, storage-eligibility — so a forgery
 * dies at the cheapest check that catches it:
 *  1. **Time clamp.** A far-future `reportedAt`/`timestamp` lets one forgery pin
 *     `getLatest()` forever (it ranks on MAX(reportedAt)). Dropped before any DB work.
 *  2. **Sender auth (GHSA-x94x-v6f7-wwpp).** Ciphertext proves who the packet is FOR,
 *     not who it is FROM, and `userid` is self-declared. Each *person* peer pins the
 *     first pair ID it sees; anything else claiming to be them is dropped before it can
 *     touch Room, the map, or notifications.
 *  3. **Storage eligibility.** Only fixes from senders with a `User` row *at batch
 *     start* ([RequestStatus.MUTUAL_CONNECTION] or [RequestStatus.AWAITING_RESPONSE])
 *     reach storage. Everyone else — strangers, pending requests, trackers — is filed
 *     as a request below and their coordinates are never stored. The eligibility set is
 *     snapshotted once so a stranger cannot bootstrap themselves mid-batch.
 *
 * Tracker rows ([UserKind.TRACKER]) never pin and never accept pair IDs: their positions
 * come from anonymous crowd reports, not relay packets. A relay packet claiming a
 * tracker ID is either a misroute or a forgery — dropped and logged, never pinned.
 */
internal suspend fun LocationTrackingService.processIncomingFixes(incoming: List<InboundFix>) {
    if (incoming.isEmpty()) return
    val now = Clock.System.now()
    val usersById = repository.getAllUsers().associateBy { it.id }
    // Eligible senders: mutual/response PERSON contacts known when this batch arrived.
    // Trackers excluded (crowd reports, not relay packets). AWAITING_REQUEST excluded:
    // they filed a request; their coordinates still prove nothing.
    val eligibleIds = usersById.values
        .filter {
            it.kind == UserKind.PERSON &&
                (it.requestStatus == RequestStatus.MUTUAL_CONNECTION ||
                    it.requestStatus == RequestStatus.AWAITING_RESPONSE)
        }
        .map { it.id }.toSet()
    val authenticated = ArrayList<LocationValue>(incoming.size)
    val strangerIds = LinkedHashSet<Long>()
    for (fix in incoming) {
        val loc = fix.location
        val senderId = loc.userid
        if (senderId == Networking.userid) continue
        if (PairAuth.isFromTheFuture(loc.reportedAt, loc.timestamp, now)) {
            Log.status(
                "FF-Heartbeat",
                "dropped future-dated fix from ${senderId.toULong()} " +
                    "reportedAt=${loc.reportedAt} timestamp=${loc.timestamp}"
            )
            continue
        }
        val peer = usersById[senderId]
        if (peer == null || peer.kind != UserKind.PERSON || senderId !in eligibleIds) {
            // Stranger, tracker claim, or not-yet-mutual: request row at most, never a pin,
            // never storage. Collected for the single insert below.
            if (peer == null && senderId != Networking.userid) strangerIds.add(senderId)
            else Log.status(
                "FF-Heartbeat",
                "dropped fix from ineligible sender ${senderId.toULong()} " +
                    "(known=${peer != null} kind=${peer?.kind} status=${peer?.requestStatus})"
            )
            continue
        }
        when (PairAuth.checkInbound(peer.inboundPairId, fix.pairId)) {
            PairAuth.InboundVerdict.ACCEPT_MATCH,
            PairAuth.InboundVerdict.ACCEPT_LEGACY,
            -> authenticated.add(loc)
            PairAuth.InboundVerdict.ACCEPT_PIN -> {
                runCatching { repository.pinInboundPairId(senderId, fix.pairId!!) }
                Log.dev(
                    "FF-Heartbeat",
                    "pinned inbound pair ID for ${senderId.toULong()} " +
                        "(preview ${PairAuth.logPreview(fix.pairId)})"
                )
                authenticated.add(loc)
            }
            PairAuth.InboundVerdict.DROP_MISMATCH,
            PairAuth.InboundVerdict.DROP_MISSING,
            -> Log.status(
                "FF-Heartbeat",
                "dropped unauthenticated fix claiming ${senderId.toULong()} " +
                    "(pair ${PairAuth.logPreview(fix.pairId)}, " +
                    "expected ${PairAuth.logPreview(peer.inboundPairId)})"
            )
        }
    }
    if (strangerIds.isNotEmpty()) {
        Log.dev("FF-Heartbeat", "newUsers to insert=${strangerIds.map { it.toULong() }}")
        repository.insertUsersIgnore(strangerIds.map {
            User(" ", null, "Unknown Location", false, RequestStatus.AWAITING_REQUEST, Clock.System.now(), null, it)
        })
    }
    processIncomingLocations(authenticated)
}

/**
 * Persists a batch of authenticated peer locations. Every element must have passed
 * [processIncomingFixes] (or be a local crowd-report path that vouches for its own
 * sender, see below) — this function performs NO auth and stores everything it gets.
 *
 * Runs on the live WebSocket reader coroutine, so it only does fast, durable work;
 * the slow best-effort part is handed to [enrichIncomingLocations].
 *
 * Crowd paths ([LocationTrackingService.pollTrackerReports],
 * [LocationTrackingService.pollPoweredOffSightings]) call this DIRECTLY, bypassing
 * [processIncomingFixes] on purpose: their fixes are anonymous finder reports restamped
 * to a tracker/owner ID locally — there is no relay sender to authenticate and no pair
 * ID to check. They MUST time-clamp before calling: those reports carry finder-set
 * timestamps through the same `getLatest()` ranking.
 */
internal suspend fun LocationTrackingService.processIncomingLocations(incoming: List<LocationValue>) {
    // A category this build cannot interpret is dropped rather than stored. Marking it is not
    // enough: getLatest() ranks on reportedAt, so it would still become the newest row for
    // that person and be drawn as their position — and at least one such category
    // (NETWORK_SIGHTING) carries someone ELSE's coordinate.
    //
    // The log names who was dropped, not just how many. This is the one failure mode that is
    // otherwise invisible: if a future build ever emits a new source as someone's primary
    // stream, that person silently disappears from this map, and "Alice was dropped" is the
    // only thread anyone will have to pull on. See the note on LocationSource before adding
    // a value that could cause it.
    val knownSource = incoming.filter { it.source != LocationSource.UNKNOWN }
    if (knownSource.size != incoming.size) {
        val dropped = incoming.filter { it.source == LocationSource.UNKNOWN }.map { it.userid.toULong() }.distinct()
        val droppedCount = incoming.size - knownSource.size
        Log.status(
            "FF-Heartbeat",
            "dropped $droppedCount fix(es) from newer peer(s) with an unrecognised source: $dropped"
        )
    }
    // Low-accuracy fixes are never used: anything worse than the intake gate never
    // reaches Room, the map, or latestLocations.
    val locList = knownSource.filter {
        it.acc.isFinite() && it.acc >= 0f && it.acc <= MAX_FIX_ACCURACY_METERS
    }
    if (locList.size != knownSource.size) {
        Log.status(
            "FF-Heartbeat",
            "dropped ${knownSource.size - locList.size} fix(es) worse than ${MAX_FIX_ACCURACY_METERS}m"
        )
    }
    if (locList.isEmpty()) return
    Log.dev(
        "FF-Heartbeat",
        "received userids=${locList.map { it.userid }.distinct().map { it.toULong() }} " +
            "self=${Networking.userid.toULong()}"
    )

    // Snapshot the previous latest-per-user BEFORE persisting the new fixes, so the
    // battery-threshold and self-waypoint comparisons below still see the prior state
    // rather than the fix we're about to store.
    val latestMap = repository.latestLocationsOnce().associateBy { it.userid }

    // Persist the raw fixes immediately, before any enrichment. Everything after this
    // (waypoint detection, reverse-geocoding via fetchAddress, notifications) is
    // best-effort: the geocoder is a slow network call, any of it can throw, and the
    // whole delivery is cancelled when the live socket reconnects mid-batch (the caller
    // also swallows exceptions). Persisting last meant a slow/failed/cancelled
    // enrichment step silently dropped the location, so getLatest() kept serving stale
    // fixes even across a force-stop. Writing here makes the fix durable no matter what
    // follows.
    repository.upsertLocations(locList)
    Log.dev("FF-Heartbeat", "upsertAll ${locList.size} locations done")

    // Enrichment runs off the reader coroutine. Reverse-geocoding is a slow network
    // call, so doing it inline stalled every subsequent inbound frame until the
    // liveness timeout force-reconnected the socket. The mutex keeps batches
    // serialized, so entry/exit and low-battery alerts still fire once each.
    serviceScope.launch {
        enrichmentMutex.withLock { enrichIncomingLocations(locList, latestMap) }
    }
}

/**
 * Best-effort follow-up to [processIncomingLocations]: recomputes waypoint
 * entry/exit, reverse-geocodes the display name, and raises the entry/exit and
 * low-battery notifications. [latestMap] is the latest-per-user snapshot taken
 * *before* the new fixes were stored, so the comparisons below see the prior state.
 */
internal suspend fun LocationTrackingService.enrichIncomingLocations(
    locList: List<LocationValue>,
    latestMap: Map<Long, LocationValue>,
) {
    val currentUsers = repository.getAllUsers()
    val currentWaypoints = repository.getAllWaypoints()
    currentUsers.forEach { user ->
        enrichOneUser(user, locList, latestMap, currentWaypoints)
    }
}

private suspend fun LocationTrackingService.enrichOneUser(
    user: User,
    locList: List<LocationValue>,
    latestMap: Map<Long, LocationValue>,
    currentWaypoints: List<Waypoint>,
) {
    // Self never receives its own published location, so fall back to the latest
    // stored fix; otherwise "me" never gets its waypoint recomputed.
    val lastLoc = if (user.id == Networking.userid) latestMap[Networking.userid]
    else locList.filter { it.userid == user.id }.maxByOrNull { it.timestamp }
    lastLoc ?: return
    val lastSavedLoc = latestMap[user.id]
    maybeNotifyLowBattery(user, lastLoc, lastSavedLoc)
    val prevId = user.lastWaypointId
    val currentId = resolveWaypointId(prevId, lastLoc, currentWaypoints)
    val currentWaypoint = currentWaypoints.find { it.id == currentId }
    val displayName = resolveDisplayName(currentWaypoint, lastLoc)
    if (currentId != prevId || displayName != user.locationName) {
        // Atomic partial update — avoids stale snapshot via copy() + upsert()
        // clobbering sharingAutoToggleAt / sendingEnabled and accidentally
        // disabling sharing when you didn't intend it.
        repository.updateLocationMeta(
            id = user.id,
            locationName = displayName,
            lastWaypointId = currentId,
            lastLocationChangeTime = lastLoc.timestamp.epochSeconds
        )
    }
    maybeNotifyEntryExit(user, prevId, currentId, currentWaypoint, displayName, currentWaypoints)
}

private fun LocationTrackingService.maybeNotifyLowBattery(
    user: User,
    lastLoc: LocationValue,
    lastSavedLoc: LocationValue?,
) {
    // A network sighting carries no battery of the owner's at all: finders seal
    // it with battery 0 (deliberately, so their own level is not leaked), and
    // openSighting stamps that 0 onto the owner. Treating it as the owner's
    // battery fired a low-battery alert the moment a powered-off phone was
    // sighted at a healthy charge. UserCard already hides sighting battery
    // for the same reason; this is the notification-side equivalent.
    if (lastLoc.source == LocationSource.NETWORK_SIGHTING) return
    if (lastLoc.battery > LOW_BATTERY_THRESHOLD) return
    if ((lastSavedLoc?.battery ?: FULL_BATTERY) <= LOW_BATTERY_THRESHOLD) return
    if (user.id == Networking.userid) return
    createNotificationWithCategory(
        user.name,
        getString(R.string.notification_low_battery, user.name),
        "BATTERY_LOW",
        user.id
    )
}

private fun LocationTrackingService.resolveWaypointId(
    prevId: Long?,
    lastLoc: LocationValue,
    currentWaypoints: List<Waypoint>,
): Long? {
    val accuracy = lastLoc.acc.toDouble()
    // A fix that cannot say which side of the boundary it is on must not move the
    // answer. A stationary phone on network fixes wanders far enough to cross and
    // re-cross a 100 m geofence every few seconds, and every crossing notified.
    if (accuracy > GEOFENCE_MAX_ACCURACY_METERS) return prevId
    // Entering needs the whole error circle inside; leaving needs it wholly
    // outside the hysteresis margin, so the edge cases stay where they were.
    val entered = currentWaypoints.find {
        havershine(it.coord, lastLoc.coord) + accuracy < it.range
    }
    if (entered != null) return entered.id
    val stillInsidePrev = prevId?.let { pid ->
        currentWaypoints.find { it.id == pid }?.let {
            havershine(it.coord, lastLoc.coord) - accuracy < it.range * WAYPOINT_EXIT_HYSTERESIS
        }
    } ?: false
    return prevId.takeIf { stillInsidePrev }
}

private suspend fun LocationTrackingService.resolveDisplayName(
    currentWaypoint: Waypoint?,
    lastLoc: LocationValue,
): String {
    // Display name: prefer the waypoint we are in, then the geocoded address.
    if (currentWaypoint != null) return currentWaypoint.name
    val address = runCatching {
        fetchAddress(lastLoc.coord.lat, lastLoc.coord.lon)
    }.getOrNull()
    return address?.featureName ?: address?.thoroughfare ?: "Unknown Location"
}

private fun LocationTrackingService.maybeNotifyEntryExit(
    user: User,
    prevId: Long?,
    currentId: Long?,
    currentWaypoint: Waypoint?,
    displayName: String,
    currentWaypoints: List<Waypoint>,
) {
    if (currentId == prevId || user.id == Networking.userid) return
    if (currentId != null) {
        val enteredName = currentWaypoint?.name ?: displayName
        notifyEntryExit(
            user,
            getString(R.string.notification_entered_waypoint, user.name, enteredName),
            arrival = true
        )
    } else if (prevId != null) {
        val exitedName = currentWaypoints.find { it.id == prevId }?.name ?: user.locationName
        notifyEntryExit(
            user,
            getString(R.string.notification_exited_waypoint, user.name, exitedName),
            arrival = false
        )
    }
}

/**
 * Forwards decrypted UWB envelopes to [UwbInbox] and fires a local
 * notification for REQUEST envelopes. Driven by the live WebSocket push.
 *
 * Powered-off recovery grants ride the same channel (see [UwbEnvelopeKind.POF_GRANT]) and are
 * handled here instead, deliberately without reaching [UwbInbox]: they are not ranging
 * traffic and have no business waking the Find Nearby screen.
 *
 * On a phone that can't do Find Nearby ([uwbAvailable] false: below Android 16 or no UWB
 * radio) a REQUEST is declined on the spot instead (#869): see [declineUnsupportedUwbRequest].
 * [sendUwb] is the reply path, swappable for tests.
 */
internal suspend fun LocationTrackingService.handleUwbEnvelopes(
    list: List<UwbEnvelope>,
    uwbAvailable: Boolean = UwbSessionManager.isAvailable(this),
    sendUwb: UwbEnvelopeSender = Networking::publishUwbMessage,
) {
    if (list.isEmpty()) return
    val users = repository.getAllUsers()
    for (envelope in list) {
        when {
            envelope.kind == UwbEnvelopeKind.POF_GRANT -> acceptPoweredOffGrant(envelope)
            envelope.kind == UwbEnvelopeKind.REQUEST && !uwbAvailable ->
                declineUnsupportedUwbRequest(envelope, users, sendUwb)
            else -> {
                UwbInbox.tryEmit(envelope)
                if (envelope.kind == UwbEnvelopeKind.REQUEST) {
                    val senderId = envelope.sender.toLong()
                    val senderName = users.firstOrNull { it.id == senderId }?.name
                        ?: getString(R.string.uwb_unknown_peer_name)
                    createUwbRequestNotification(senderName, senderId)
                }
            }
        }
    }
}

/**
 * Answer a Find Nearby REQUEST this phone can never serve with an immediate CANCEL carrying
 * [UwbCancelReason.UNSUPPORTED], so the requester shows "their phone doesn't support it"
 * instead of waiting out its 60 s timeout.
 *
 * The request is not forwarded to [UwbInbox] and posts no notification: tapping it could only
 * open a screen that fails, and on an Android 16 phone without a radio the inbox would also
 * start the auto-accept path in [UwbSessionManager], which fails silently. The reply goes out
 * on [LocationTrackingService.serviceScope] because publishing is a network round trip and
 * this runs on the live socket's reader coroutine.
 *
 * Broad catch is deliberate, same as `UwbSessionManager.publish`: the reply crosses Room and
 * the relay socket, and a failed courtesy reply must not take the scope down with it.
 */
@Suppress("TooGenericExceptionCaught")
private fun LocationTrackingService.declineUnsupportedUwbRequest(
    request: UwbEnvelope,
    users: List<User>,
    sendUwb: UwbEnvelopeSender,
) {
    val senderId = request.sender.toLong()
    if (senderId == Networking.userid) return
    val decline = UwbEnvelope(
        sessionId = request.sessionId,
        sender = Networking.userid.toULong(),
        senderPlatform = "android",
        kind = UwbEnvelopeKind.CANCEL,
        reason = UwbCancelReason.UNSUPPORTED,
    )
    val peer = users.firstOrNull { it.id == senderId }
    serviceScope.launch {
        val sent = try {
            sendUwb(decline, senderId, peer)
        } catch (e: Exception) {
            Log.status(TAG_UWB, "decline reply to ${senderId.toULong()} threw", e)
            false
        }
        Log.status(TAG_UWB, "declined Find Nearby request from ${senderId.toULong()} (no UWB here), sent=$sent")
    }
}

/**
 * Store a family member's powered-off keys so this device can go and find their lost phone.
 *
 * Everything after this is already built: [com.vayunmathur.findfamily.tracker.PoweredOffKeyStore]
 * is keyed by userid and [pollPoweredOffSightings] already walks every user it can read, so filing
 * the keys here is the entire receiving side.
 *
 * Three reasons to refuse, all of them silent by design — a rejected grant is either an
 * attack or a stale duplicate, and neither is worth a notification:
 *  - the sender is not someone we have a [User] row for, so nobody chose to trust them;
 *  - the signature does not verify against that sender's identity bundle. The envelope is
 *    encrypted to us but its `sender` field is self-declared, so without this check any
 *    connected peer could deliver keys under a different family member's userid and have
 *    every sighting decrypted from them drawn on the map as that person's phone;
 *  - the grant is older than one we already hold, which happens when a revoke-triggered
 *    redistribution overtakes the grant it replaces.
 */
private fun LocationTrackingService.grantContextOrNull(
    envelope: UwbEnvelope,
): Triple<PoweredOffKeyStore, PoweredOffGrant, Long>? {
    val store = poweredOffKeys ?: return null
    val grant = envelope.recovery ?: return null
    val ownerId = envelope.sender.toLong()
    if (ownerId == 0L || ownerId == Networking.userid) return null
    return Triple(store, grant, ownerId)
}

internal suspend fun LocationTrackingService.acceptPoweredOffGrant(envelope: UwbEnvelope) {
    val (store, grant, ownerId) = grantContextOrNull(envelope) ?: return
    if (repository.getUser(ownerId) == null) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "recovery grant from unknown sender, ignored")
        return
    }
    val decoded = runCatching {
        Triple(
            Base64.decode(grant.secretB64),
            Base64.decode(grant.recoveryPrivB64),
            Base64.decode(grant.sigB64),
        )
    }.getOrNull() ?: return
    val (secret, recoveryPriv, signature) = decoded

    val signed = poweredOffGrantSigningBytes(
        owner = ownerId,
        recipient = Networking.userid,
        epoch = grant.epoch,
        secret = secret,
        recoveryPrivate = recoveryPriv,
    )
    if (!Networking.verifyFrom(ownerId, signed, signature)) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "recovery grant signature did not verify, ignored")
        return
    }
    if (grant.epoch < store.epoch(ownerId)) {
        Log.status(LocationTrackingService.TAG_POWERED_OFF, "ignoring superseded recovery grant (epoch ${grant.epoch})")
        return
    }
    store.save(ownerId, secret, recoveryPriv, grant.epoch)
    Log.status(LocationTrackingService.TAG_POWERED_OFF, "stored recovery keys for a peer (epoch ${grant.epoch})")
}
