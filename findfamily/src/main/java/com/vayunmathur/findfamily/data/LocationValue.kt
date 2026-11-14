package com.vayunmathur.findfamily.data

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Why a fix was published, so a receiver can tell a live report from a device's parting shot.
 *
 * Stored by Room as the enum name and carried on the wire as that same string. A value this
 * build does not recognise decodes to [UNKNOWN] rather than throwing or being guessed at — see
 * [LocationValueCompatible].
 *
 * ## Adding a value here is a wire-compatibility decision. Read this first.
 *
 * A peer older than your new value will **drop** fixes carrying it (see [UNKNOWN]). That is only
 * safe because every value below except [LIVE] is *supplementary*: a device still emits [LIVE]
 * for its ordinary heartbeat, so an older peer that discards the rest still tracks the person
 * normally and merely misses the extra context.
 *
 * **Do not add a value that becomes a device's primary or only report stream.** If you do, that
 * person does not appear stale on older peers — they vanish outright, with no pin and no error,
 * and the user cannot tell "they stopped sharing" from "my build is too old to understand them".
 * In a family-location app that silence is its own safety failure.
 *
 * If you genuinely need a new primary stream, change the receiving policy first: ship a build
 * that downgrades rather than drops, wait for it to reach the fleet, and only then start
 * emitting. The drop cannot be the thing you rely on.
 */
@Serializable
enum class LocationSource {
    /** The ordinary heartbeat, roughly as current as the fix age allows. */
    LIVE,

    /** Sent while the device was powering down or rebooting. The position is wherever it last was. */
    SHUTDOWN,

    /** Sent when the battery crossed the system low threshold, before the device dies on its own. */
    BATTERY_LOW,

    /**
     * Not sent by the device at all. Another phone running findfamily heard this device's
     * powered-off beacon and reported *its own* position as a proxy. The coordinate is where the
     * finder was standing, not where the device is, so it is only as precise as BLE range —
     * treat it as "somewhere within a block or so of here", never as a fix.
     */
    NETWORK_SIGHTING,

    /**
     * A source string this build has never heard of, from a peer newer than it. Never published —
     * decode-only.
     *
     * This exists because the categories are not interchangeable. [NETWORK_SIGHTING] is the
     * proof: its coordinate is *someone else's* position, so a build that guessed "probably
     * [LIVE]" would render a stranger's location as a precise, current fix of the person being
     * looked for, and send whoever is searching to the wrong place. That is the worst direction
     * for this app to be wrong in, and there is no way to know in advance which future category
     * carries the same trap.
     *
     * So an unrecognised source is treated as unusable rather than assumed benign, and inbound
     * fixes carrying it are dropped (see `LocationTrackingService.processIncomingLocations`).
     * That is safe only while new categories stay supplementary to [LIVE] — see the note on the
     * enum itself before adding one. Note this is distinct from a peer that omits the field
     * entirely: those predate the field, only ever sent live reports, and correctly decode
     * to [LIVE].
     */
    UNKNOWN,
}

@Serializable
@Entity(indices = [Index(value = ["userid", "timestamp"]), Index(value = ["userid", "reportedAt"])])
/** One stored location report for a person, with measured and sent timestamps. */
data class LocationValue(
    val userid: Long,
    @Embedded val coord: Coord,
    val speed: Float,
    val acc: Float,
    /**
     * When the position was actually measured — not when it was sent.
     *
     * The distinction only bites on [LocationSource.SHUTDOWN] and
     * [LocationSource.BATTERY_LOW] reports, which deliberately publish a possibly-stale
     * last known fix rather than wait for a fresh one. Keeping the measurement time here is
     * what lets "last seen" stay truthful instead of claiming the phone was there at the
     * moment it switched off. [reportedAt] carries the other half.
     */
    @ColumnInfo(index = true)
    val timestamp: Instant,
    val battery: Float,
    /**
     * When the report was sent, as opposed to when the position in it was measured.
     *
     * Equal to [timestamp] on live reports and later than it on shutdown and low-battery ones.
     * This, not [timestamp], is what "which report is newest" has to be decided on: a parting
     * report carries a deliberately stale fix, so ranking by measurement time would let the live
     * heartbeat that preceded it win and the shutdown would never surface. See
     * `LocationValueDao.getLatest`.
     */
    val reportedAt: Instant = timestamp,
    val source: LocationSource = LocationSource.LIVE,
    @PrimaryKey(autoGenerate = true) override val id: Long = 0
): DatabaseItem {
    fun toCompatible(senderPlatform: String? = null, pairId: String? = null): LocationValueCompatible {
        return LocationValueCompatible(
            userid = userid.toULong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = timestamp.toEpochMilliseconds(),
            battery = battery,
            sleep = false,
            id = id.toULong(),
            senderPlatform = senderPlatform,
            pairId = pairId,
            reportedAt = reportedAt.toEpochMilliseconds(),
            source = source.name,
        )
    }
}

@Serializable
/** Wire form of [LocationValue] exchanged with peers, using epoch millis and unsigned ids. */
data class LocationValueCompatible(
    val id: ULong = 0uL,
    val userid: ULong,
    val coord: Coord,
    val speed: Float,
    val acc: Float,
    val timestamp: Long,
    val battery: Float,
    val sleep: Boolean? = null,
    /** Sender's platform tag (`"android"` or `"ios"`). Optional for backward compatibility. */
    val senderPlatform: String? = null,
    /**
     * Hidden sender-auth secret (GHSA-x94x-v6f7-wwpp): this sender's outbound pair ID
     * for the recipient. Base64url unpadded, ~22 chars. Optional for backward
     * compatibility — absent means a peer that predates sender authentication.
     */
    val pairId: String? = null,
    /** [LocationValue.reportedAt] in epoch milliseconds. Absent from peers that predate it, in
     * which case it falls back to [timestamp] — an older peer only ever sent live reports. */
    val reportedAt: Long? = null,
    /**
     * [LocationSource] by name. A plain string rather than the enum so a value this build has
     * never heard of degrades to [LocationSource.UNKNOWN] instead of failing the whole decode —
     * iOS and older Android peers have to keep interoperating. Absent entirely (null) means the
     * peer predates the field and only ever sent live reports, which is [LocationSource.LIVE].
     */
    val source: String? = null
) {
    fun toLocationValue(): LocationValue {
        return LocationValue(
            userid = userid.toLong(),
            coord = coord,
            speed = speed,
            acc = acc,
            timestamp = Instant.fromEpochMilliseconds(timestamp),
            battery = battery,
            reportedAt = Instant.fromEpochMilliseconds(reportedAt ?: timestamp),
            // Absent means a peer older than the field, which only ever sent live reports.
            // Present but unrecognised means a peer NEWER than this build, whose category we
            // cannot interpret — guessing LIVE there would be unsafe, see LocationSource.UNKNOWN.
            source = when (source) {
                null -> LocationSource.LIVE
                else -> LocationSource.entries.firstOrNull { it.name == source }
                    ?: LocationSource.UNKNOWN
            },
            // NOT id = id.toLong(): `id` is the *sender's* local autogenerate primary
            // key. Reusing it here makes @Upsert collide across devices (every device's
            // ids grow from 1 in lockstep), so peers overwrite each other's — and your
            // own — rows by PK, leaving getLatest() with stale data. Use 0 so Room
            // assigns a fresh local id and each received fix is stored as its own row.
            id = 0,
        )
    }
}
