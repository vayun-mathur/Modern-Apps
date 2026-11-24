package com.vayunmathur.clock.wear.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * Phone <-> watch sync protocol for the Wear OS Clock companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:clock:wear`,
 * applicationId `com.vayunmathur.clock`) carries the other copy of this file under
 * `com.vayunmathur.clock.domain`. Change both copies together; each
 * side's unit tests pin the same golden JSON so a one-sided edit fails loudly.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items carry the snapshot: [SNAPSHOT_PATH] phone -> watch and
 *   [WATCH_STATE_PATH] watch -> phone. Delivery needs a live session; each
 *   side persists what it receives and republishes on reconnect.
 * - Messages carry one ephemeral command, [REQUEST_SYNC_PATH] watch -> phone.
 *
 * What is synced and what is not: alarms and the world-clock list are the
 * phone's, published read-only as a snapshot. Timers and the stopwatch are
 * watch-native (they live and run on the watch) and are never in this
 * snapshot — a snapshot timer would be a display of a phone timer, which
 * could not ring on the watch while the phone is absent.
 */
object WearClockProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "clock_wear_companion"

    /** Data-item path (phone -> watch): the [WearClockSnapshot] JSON. */
    const val SNAPSHOT_PATH = "/clock/snapshot"

    /** Data-item path (watch -> phone): the [WearClockWatchState] JSON. */
    const val WATCH_STATE_PATH = "/clock/watch-state"

    /** Message path (watch -> phone): ask the phone to publish a fresh snapshot. */
    const val REQUEST_SYNC_PATH = "/clock/request-sync"

    /** Most alarms in one snapshot; a watch renders a short list. */
    const val MAX_ALARMS = 50

    /** Most world clocks in one snapshot, in the phone's list order. */
    const val MAX_WORLD_CLOCKS = 20

    /** Largest snapshot JSON, in UTF-8 bytes (WearLink chunks it either way). */
    const val MAX_JSON_BYTES = 90_000

    const val MAX_LABEL_CHARS = 60
    const val MAX_CITY_CHARS = 80

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One phone alarm, trimmed to what the watch needs to arm and render it. */
@Serializable
data class WearClockAlarm(
    /** Phone Room alarm id; stable identity across snapshots. */
    val alarmId: Long,
    val label: String = "",
    val hour: Int,
    val minute: Int,
    /** Weekday bitmask, bit 0 = Sunday (same encoding as the phone Alarm). 0 = one-shot. */
    val daysMask: Int = 0,
    val enabled: Boolean = false,
    /**
     * Phone-computed next fire, epoch millis (0 when disabled/unknown). For
     * display comparison only: the watch computes and arms its own next fire
     * from [hour]/[minute]/[daysMask] in its own timezone.
     */
    val phoneNextTriggerMillis: Long = 0L,
    val vibrate: Boolean = true,
    val snoozeMinutes: Int = 5,
)

/** One world-clock entry: a city and the IANA zone the phone resolved it to. */
@Serializable
data class WearWorldClock(
    val city: String,
    val timezoneId: String,
)

/** The phone's bounded clock snapshot for the watch ([WearClockProtocol.SNAPSHOT_PATH]). */
@Serializable
data class WearClockSnapshot(
    /** When the phone built this snapshot, epoch millis. */
    val generatedAtMillis: Long = 0L,
    val alarms: List<WearClockAlarm> = emptyList(),
    /** In the phone's world-clock list order. */
    val worldClocks: List<WearWorldClock> = emptyList(),
    /** True when the count/byte caps dropped entries. */
    val truncated: Boolean = false,
)

/** What the watch last applied and armed, at [WearClockProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearClockWatchState(
    /** generatedAtMillis of the snapshot the watch is rendering from, 0 when none yet. */
    val appliedGeneratedAtMillis: Long = 0L,
    val alarmCount: Int = 0,
    /** Alarms actually armed with the watch's AlarmManager (never display-only ones). */
    val armedCount: Int = 0,
    /** Enabled alarms the watch could not arm and only displays. */
    val displayOnlyCount: Int = 0,
)

/**
 * Builds a bounded snapshot: alarms sorted by time of day (then id), world
 * clocks kept in phone order, field caps applied, then
 * [WearClockProtocol.MAX_ALARMS]/[WearClockProtocol.MAX_WORLD_CLOCKS] and
 * [WearClockProtocol.MAX_JSON_BYTES] enforced (world clocks drop first —
 * alarms that must ring outrank clocks that only display).
 */
fun buildWearClockSnapshot(
    alarms: List<WearClockAlarm>,
    worldClocks: List<WearWorldClock>,
    generatedAtMillis: Long,
): WearClockSnapshot {
    val cappedAlarms = alarms
        .sortedWith(compareBy({ it.hour }, { it.minute }, { it.alarmId }))
        .map { it.copy(label = it.label.take(WearClockProtocol.MAX_LABEL_CHARS)) }
    val cappedWorld = worldClocks.map {
        it.copy(city = it.city.take(WearClockProtocol.MAX_CITY_CHARS))
    }
    var keptAlarms = cappedAlarms.take(WearClockProtocol.MAX_ALARMS)
    var keptWorld = cappedWorld.take(WearClockProtocol.MAX_WORLD_CLOCKS)
    var truncated = keptAlarms.size < cappedAlarms.size || keptWorld.size < cappedWorld.size
    var snapshot = WearClockSnapshot(generatedAtMillis, keptAlarms, keptWorld, truncated)
    fun bytes() = WearClockProtocol.json.encodeToString(
        WearClockSnapshot.serializer(), snapshot,
    ).encodeToByteArray().size
    while (bytes() > WearClockProtocol.MAX_JSON_BYTES && keptWorld.isNotEmpty()) {
        keptWorld = keptWorld.dropLast(1)
        truncated = true
        snapshot = WearClockSnapshot(generatedAtMillis, keptAlarms, keptWorld, true)
    }
    while (bytes() > WearClockProtocol.MAX_JSON_BYTES && keptAlarms.isNotEmpty()) {
        keptAlarms = keptAlarms.dropLast(1)
        truncated = true
        snapshot = WearClockSnapshot(generatedAtMillis, keptAlarms, keptWorld, true)
    }
    return snapshot
}

/**
 * Next fire instant (epoch millis) for [hour]:[minute] with weekday
 * [daysMask] (bit 0 = Sunday, 0 = one-shot) at or after [nowMillis] in
 * [timeZone]. Calendar-based day stepping, matching the phone scheduler:
 * adding a fixed 24h would land an hour off across DST transitions.
 * An alarm whose time is exactly now fires tomorrow / on its next day.
 */
fun nextWearAlarmTriggerMillis(
    hour: Int,
    minute: Int,
    daysMask: Int,
    nowMillis: Long,
    timeZone: TimeZone,
): Long {
    val now = Instant.fromEpochMilliseconds(nowMillis)
    val today = now.toLocalDateTime(timeZone).date
    var candidate = LocalDateTime(today, kotlinx.datetime.LocalTime(hour, minute))
    if (candidate.toInstant(timeZone) <= now) {
        candidate = LocalDateTime(
            candidate.date.plus(1, DateTimeUnit.DAY), candidate.time,
        )
    }
    if (daysMask == 0) return candidate.toInstant(timeZone).toEpochMilliseconds()
    repeat(7) {
        val bit = when (candidate.dayOfWeek) {
            kotlinx.datetime.DayOfWeek.SUNDAY -> 0
            else -> candidate.dayOfWeek.ordinal + 1 // Mon ordinal 0 -> bit 1
        }
        if (daysMask and (1 shl bit) != 0) {
            return candidate.toInstant(timeZone).toEpochMilliseconds()
        }
        candidate = LocalDateTime(
            candidate.date.plus(1, DateTimeUnit.DAY), candidate.time,
        )
    }
    return candidate.toInstant(timeZone).toEpochMilliseconds()
}
