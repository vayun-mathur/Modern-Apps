package com.vayunmathur.findfamily.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone <-> watch protocol for the Wear OS FindFamily companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module
 * (`:findfamily-wear`, applicationId `com.vayunmathur.findfamily`) carries
 * a copy of this file under `com.vayunmathur.findfamily.wear.domain`.
 * Change both copies together; each side's unit tests pin the same golden
 * JSON so a one-sided edit fails loudly.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items: [SNAPSHOT_PATH] phone -> watch (all-people location
 *   summaries) and [WATCH_STATE_PATH] watch -> phone.
 * - Messages: [OP_PATH] watch -> phone (stop-sharing / request-refresh)
 *   and [REQUEST_SYNC_PATH] watch -> phone (ask for a fresh snapshot).
 *   Delivery needs a live session; nothing wakes a dead phone process.
 *
 * Owner rule: the watch shows ALL people in the phone circle. The payload
 * is bounded by summarising (short labels, E7 coordinates, no history),
 * never by a top-N people cap in normal operation. Only if the byte cap
 * still cannot fit everyone does [buildWearSnapshot] drop the
 * least-recently-updated people, and then it says so honestly via
 * `truncated` + `droppedCount`, keeping most-recent first.
 *
 * Privacy: a person entry is exactly what the phone's own cards show —
 * current place label, current coordinate at E7 precision, fix/report
 * times, accuracy, speed-derived motion, battery. No location history,
 * no track, no keys, no pair ids. E2EE stays phone-side: the phone
 * decrypts peer reports into its Room store and only these already-
 * decrypted summaries cross WearLink (Bluetooth-bonded, cert-bound).
 */
object WearFindFamilyProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "findfamily_wear_companion"

    /** Data-item path (phone -> watch): the [WearFindFamilySnapshot] JSON. */
    const val SNAPSHOT_PATH = "/findfamily/snapshot"

    /** Data-item path (watch -> phone): the [WearWatchState] JSON. */
    const val WATCH_STATE_PATH = "/findfamily/watch-state"

    /** Message path (watch -> phone): one [WearFindFamilyOp] JSON. */
    const val OP_PATH = "/findfamily/op"

    /** Message path (watch -> phone): ask the phone to publish a fresh snapshot. */
    const val REQUEST_SYNC_PATH = "/findfamily/request-sync"

    /** Largest snapshot JSON in UTF-8 bytes; summarise/drop-oldest to fit. */
    const val MAX_JSON_BYTES = 90_000

    /** Field caps applied when a snapshot is built. */
    const val MAX_NAME_CHARS = 60
    const val MAX_PLACE_CHARS = 80

    /** Tighter caps used as the first summarising step before anyone is dropped. */
    const val SUMMARISED_NAME_CHARS = 24
    const val SUMMARISED_PLACE_CHARS = 24

    /** A fix older than this (watch clock vs fix time) reads stale, never live. */
    const val STALE_AFTER_MS = 30 * 60_000L

    /** Speed (m/s) at/above which a person counts as moving for the watch summary. */
    const val MOVING_SPEED_MPS = 1.0

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** Motion summary derived phone-side from the fix's reported speed. */
@Serializable
enum class WearMotion { MOVING, STATIONARY, UNKNOWN }

/** Kind of watch-readable alert state carried in a snapshot. */
@Serializable
enum class WearAlertKind { AT_PLACE, NOSHOW }

/** One person trimmed to watch size: the phone card, summarised. No history. */
@Serializable
data class WearPerson(
    val id: Long,
    val name: String,
    /** Saved-place/locality label the phone UI shows ("Home"), "" when unnamed. */
    val placeLabel: String = "",
    /** When the person arrived at [placeLabel] (phone `lastLocationChangeTime`), 0 unknown. */
    val placeSinceMillis: Long = 0L,
    /** Current coordinate, degrees * 1e7 (the precision the phone map shows); null = no fix. */
    val latE7: Int? = null,
    val lonE7: Int? = null,
    /** Fix accuracy radius (m); -1 when the source gives none (e.g. network sighting). */
    val accuracyMeters: Int = -1,
    /** When the position was measured (epoch millis); 0 = no fix. */
    val updatedAtMillis: Long = 0L,
    /** When the report was sent; differs from measured time on parting reports. */
    val reportedAtMillis: Long = 0L,
    /** Battery percent 0..100; -1 when unavailable (incl. network sightings). */
    val batteryPercent: Int = -1,
    val motion: WearMotion = WearMotion.UNKNOWN,
    /** Wire source name (LIVE/SHUTDOWN/BATTERY_LOW/NETWORK_SIGHTING); "" when no fix. */
    val source: String = "",
    /** Whether the phone is sharing this device's location with this person. */
    val sharingEnabled: Boolean = true,
    val isSelf: Boolean = false,
)

/**
 * Watch-readable alert state. The phone persists current place + since
 * (not an enter/leave event log), so AT_PLACE is the honest enter state;
 * NOSHOW mirrors the phone's no-show rows (pending and fired).
 */
@Serializable
data class WearAlert(
    val kind: WearAlertKind,
    val personId: Long,
    val placeLabel: String = "",
    val atMillis: Long = 0L,
    val fired: Boolean = false,
)

/** Bounded region around the snapshot centre with everyone's markers relative to it. */
@Serializable
data class WearRegion(
    val centerLatE7: Int = 0,
    val centerLonE7: Int = 0,
    /** Radius (m) covering centre -> farthest located person, clamped for rendering. */
    val radiusM: Int = 200,
    val hasCenter: Boolean = false,
)

/** The phone's all-people snapshot for the watch ([WearFindFamilyProtocol.SNAPSHOT_PATH]). */
@Serializable
data class WearFindFamilySnapshot(
    val generatedAtMillis: Long = 0L,
    /** Every person in the circle, most recently updated first. */
    val people: List<WearPerson> = emptyList(),
    val alerts: List<WearAlert> = emptyList(),
    /** Global sharing switch on the phone (Quick Settings tile semantics). */
    val sharingEnabled: Boolean = true,
    val region: WearRegion = WearRegion(),
    /** True only when the byte cap forced people out; see [droppedCount]. */
    val truncated: Boolean = false,
    val droppedCount: Int = 0,
    /** People before any cap drop, so the watch can state the full circle size. */
    val totalPeople: Int = 0,
)

/** What the watch last applied, published at [WearFindFamilyProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearWatchState(
    val appliedGeneratedAtMillis: Long = 0L,
    val peopleCount: Int = 0,
)

/** Watch-originated actions; the phone remains the source of truth for both. */
@Serializable
enum class WearOpKind { STOP_SHARING_NOW, REQUEST_REFRESH }

/** One watch-originated action, as [WearFindFamilyProtocol.OP_PATH]. */
@Serializable
data class WearFindFamilyOp(
    val kind: WearOpKind,
    val requestedAtMillis: Long = 0L,
)

/** Motion from reported speed; no speed (or a sighting) is honestly UNKNOWN. */
fun wearMotionFor(speedMps: Float?, hasFix: Boolean): WearMotion = when {
    !hasFix || speedMps == null || !speedMps.isFinite() -> WearMotion.UNKNOWN
    speedMps >= WearFindFamilyProtocol.MOVING_SPEED_MPS -> WearMotion.MOVING
    else -> WearMotion.STATIONARY
}

/** True when a person's fix is missing or older than the stale window. */
fun wearIsStale(person: WearPerson, nowMillis: Long): Boolean =
    person.updatedAtMillis <= 0L || nowMillis - person.updatedAtMillis > WearFindFamilyProtocol.STALE_AFTER_MS

/** True when the whole snapshot has aged out (offline reading must label it). */
fun wearSnapshotIsStale(snapshot: WearFindFamilySnapshot?, nowMillis: Long): Boolean =
    snapshot == null || nowMillis - snapshot.generatedAtMillis > WearFindFamilyProtocol.STALE_AFTER_MS

/** Haversine distance (m) between two E7 positions. */
fun wearDistanceMeters(lat1E7: Int, lon1E7: Int, lat2E7: Int, lon2E7: Int): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians((lat2E7 - lat1E7) / 1e7)
    val dLon = Math.toRadians((lon2E7 - lon1E7) / 1e7)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1E7 / 1e7)) * cos(Math.toRadians(lat2E7 / 1e7)) *
        sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
}

/** Initial bearing (degrees clockwise from north, 0..359) from first E7 point to second. */
fun wearBearingDegrees(lat1E7: Int, lon1E7: Int, lat2E7: Int, lon2E7: Int): Int {
    val lat1 = Math.toRadians(lat1E7 / 1e7)
    val lat2 = Math.toRadians(lat2E7 / 1e7)
    val dLon = Math.toRadians((lon2E7 - lon1E7) / 1e7)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val deg = Math.toDegrees(atan2(y, x))
    return ((deg % 360.0) + 360.0).toInt() % 360
}

/** 8-wind compass label for a bearing ("N", "NE", ...). */
fun wearCompass(bearingDeg: Int): String {
    val winds = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return winds[(((bearingDeg % 360) + 360) % 360 + 22) / 45 % 8]
}

/** Metres east/north of an origin for a target E7 position (relative-view math). */
fun wearRelativeOffsetMeters(
    originLatE7: Int,
    originLonE7: Int,
    latE7: Int,
    lonE7: Int,
): Pair<Double, Double> {
    val east = (lonE7 - originLonE7) / 1e7 * 111_320.0 * cos(Math.toRadians(originLatE7 / 1e7))
    val north = (latE7 - originLatE7) / 1e7 * 110_540.0
    return east to north
}

/** Region centred on self (else first located person) covering every located person. */
fun wearRegionFor(people: List<WearPerson>): WearRegion {
    val located = people.filter { it.latE7 != null && it.lonE7 != null }
    if (located.isEmpty()) return WearRegion()
    val centre = located.firstOrNull { it.isSelf } ?: located.first()
    val maxDist = located.maxOf {
        wearDistanceMeters(centre.latE7!!, centre.lonE7!!, it.latE7!!, it.lonE7!!)
    }
    return WearRegion(
        centerLatE7 = centre.latE7!!,
        centerLonE7 = centre.lonE7!!,
        radiusM = maxDist.toInt().coerceIn(200, 100_000),
        hasCenter = true,
    )
}

/**
 * Builds the all-people snapshot: most-recently-updated first, field caps
 * applied, then — only to fit [WearFindFamilyProtocol.MAX_JSON_BYTES] —
 * tighter summarising, and only then dropping least-recent people with
 * `truncated`/`droppedCount` set. Normal circles lose nobody.
 */
fun buildWearSnapshot(
    candidates: List<WearPerson>,
    alerts: List<WearAlert> = emptyList(),
    sharingEnabled: Boolean = true,
    generatedAtMillis: Long,
): WearFindFamilySnapshot {
    val sorted = candidates.sortedWith(
        compareByDescending<WearPerson> { it.updatedAtMillis }.thenBy { it.id },
    ).map {
        it.copy(
            name = it.name.take(WearFindFamilyProtocol.MAX_NAME_CHARS),
            placeLabel = it.placeLabel.take(WearFindFamilyProtocol.MAX_PLACE_CHARS),
        )
    }
    fun snap(people: List<WearPerson>, dropped: Int) = WearFindFamilySnapshot(
        generatedAtMillis = generatedAtMillis,
        people = people,
        alerts = alerts,
        sharingEnabled = sharingEnabled,
        region = wearRegionFor(people),
        truncated = dropped > 0,
        droppedCount = dropped,
        totalPeople = sorted.size,
    )
    var kept = sorted
    var snapshot = snap(kept, 0)
    if (WearFindFamilyProtocol.json.encodeToString(snapshot).encodeToByteArray().size <=
        WearFindFamilyProtocol.MAX_JSON_BYTES
    ) return snapshot
    // Summarise harder before dropping anyone.
    kept = kept.map {
        it.copy(
            name = it.name.take(WearFindFamilyProtocol.SUMMARISED_NAME_CHARS),
            placeLabel = it.placeLabel.take(WearFindFamilyProtocol.SUMMARISED_PLACE_CHARS),
        )
    }
    snapshot = snap(kept, 0)
    while (kept.isNotEmpty() &&
        WearFindFamilyProtocol.json.encodeToString(snapshot).encodeToByteArray().size >
        WearFindFamilyProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        snapshot = snap(kept, sorted.size - kept.size)
    }
    return snapshot
}
