package com.vayunmathur.calendar.wear.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch sync protocol for the Wear OS Calendar companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: the Wearable Data Layer only
 * connects apps that share a package name and signing certificate, but Gradle
 * cannot let one application module depend on another, so the wear module
 * (`:calendar:wear`, applicationId `com.vayunmathur.calendar`) carries a copy
 * of this file under `com.vayunmathur.calendar.wear.domain`. Change both
 * copies together; each side's unit tests pin the same golden JSON so a
 * one-sided edit fails loudly on the side that was not updated.
 *
 * Transport split:
 * - Capability advertisement (res string-array `android_wear_capabilities` on
 *   the watch) tells the phone the wear companion is present at all.
 * - DataClient carries the durable snapshot: [SNAPSHOT_PATH] phone -> watch
 *   and [WATCH_STATE_PATH] watch -> phone. DataItems survive disconnects and
 *   reconcile the two sides when they meet again.
 * - MessageClient carries one ephemeral command, [REQUEST_SYNC_PATH]
 *   watch -> phone: the watch asks for a fresh snapshot. That is the only
 *   watch-originated write. There is no event editing or RSVP: the phone's
 *   calendar model has no attendee/self-status for its local calendars, so
 *   inventing an RSVP write would fake a change the provider never made.
 */
object WearCalendarProtocol {
    /** Capability the wear companion advertises; the phone looks for it on connected nodes. */
    const val CAPABILITY = "calendar_wear_companion"

    /** DataClient path (phone -> watch): the [WearCalendarSnapshot] JSON. */
    const val SNAPSHOT_PATH = "/calendar/snapshot"

    /** DataClient path (watch -> phone): the [WearWatchState] JSON. */
    const val WATCH_STATE_PATH = "/calendar/watch-state"

    /** MessageClient path (watch -> phone): ask the phone to publish a fresh snapshot. */
    const val REQUEST_SYNC_PATH = "/calendar/request-sync"

    /** Days ahead of the window start included in a snapshot. */
    const val WINDOW_DAYS = 30

    /** Most events in one snapshot; a watch has little storage and a tiny screen. */
    const val MAX_EVENTS = 200

    /**
     * Largest snapshot JSON, in UTF-8 bytes. DataClient caps a DataItem at
     * 100 KB; staying under it keeps the snapshot on DataClient (durable,
     * reconciled on reconnect) instead of needing a channel.
     */
    const val MAX_JSON_BYTES = 90_000

    /** Field caps applied when a snapshot is built, so one huge event cannot crowd out the rest. */
    const val MAX_TITLE_CHARS = 120
    const val MAX_LOCATION_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 280
    const val MAX_CALENDAR_NAME_CHARS = 60

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** One event occurrence in a [WearCalendarSnapshot], trimmed to watch size. */
@Serializable
data class WearCalendarEvent(
    /** Phone provider event id; with [startMillis] it identifies one occurrence. */
    val eventId: Long,
    val title: String,
    val location: String = "",
    val description: String = "",
    val calendarName: String = "",
    /** ARGB display colour of the owning calendar, 0 when unknown. */
    val color: Int = 0,
    val startMillis: Long,
    val endMillis: Long,
    val allDay: Boolean = false,
    val timezone: String = "UTC",
)

/** The phone's bounded calendar snapshot for the watch ([WearCalendarProtocol.SNAPSHOT_PATH]). */
@Serializable
data class WearCalendarSnapshot(
    /** When the phone built this snapshot, epoch millis. */
    val generatedAtMillis: Long = 0L,
    /** Inclusive window start, epoch millis; the watch renders only inside the window. */
    val windowStartMillis: Long = 0L,
    /** Exclusive window end, epoch millis (window start + [WearCalendarProtocol.WINDOW_DAYS] days). */
    val windowEndMillis: Long = 0L,
    val events: List<WearCalendarEvent> = emptyList(),
    /** True when the window/size caps dropped events the window actually contained. */
    val truncated: Boolean = false,
)

/** What the watch last applied, published at [WearCalendarProtocol.WATCH_STATE_PATH]. */
@Serializable
data class WearWatchState(
    /** generatedAtMillis of the snapshot the watch is rendering from, 0 when none yet. */
    val appliedGeneratedAtMillis: Long = 0L,
    val eventCount: Int = 0,
)

/**
 * Builds a bounded snapshot from [candidates]: keeps occurrences that overlap
 * [[windowStartMillis], [windowEndMillis]), sorts by start, applies the field
 * caps, then enforces [WearCalendarProtocol.MAX_EVENTS] and
 * [WearCalendarProtocol.MAX_JSON_BYTES] (dropping latest-first, which keeps
 * today's agenda — the watch's main screen — intact longest).
 */
fun buildWearSnapshot(
    candidates: List<WearCalendarEvent>,
    generatedAtMillis: Long,
    windowStartMillis: Long,
    windowEndMillis: Long,
): WearCalendarSnapshot {
    val inWindow = candidates
        .filter { it.endMillis > windowStartMillis && it.startMillis < windowEndMillis }
        .sortedWith(compareBy({ it.startMillis }, { it.eventId }))
        .map { event ->
            event.copy(
                title = event.title.take(WearCalendarProtocol.MAX_TITLE_CHARS),
                location = event.location.take(WearCalendarProtocol.MAX_LOCATION_CHARS),
                description = event.description.take(WearCalendarProtocol.MAX_DESCRIPTION_CHARS),
                calendarName = event.calendarName.take(WearCalendarProtocol.MAX_CALENDAR_NAME_CHARS),
            )
        }
    var kept = inWindow.take(WearCalendarProtocol.MAX_EVENTS)
    var truncated = kept.size < inWindow.size
    var snapshot = WearCalendarSnapshot(
        generatedAtMillis = generatedAtMillis,
        windowStartMillis = windowStartMillis,
        windowEndMillis = windowEndMillis,
        events = kept,
        truncated = truncated,
    )
    while (kept.isNotEmpty() &&
        WearCalendarProtocol.json.encodeToString(snapshot).encodeToByteArray().size >
        WearCalendarProtocol.MAX_JSON_BYTES
    ) {
        kept = kept.dropLast(1)
        truncated = true
        snapshot = snapshot.copy(events = kept, truncated = true)
    }
    return snapshot
}

/** True when [nodeIdsWithCapability] is non-empty: a watch companion is present. */
fun wearCompanionPresent(nodeIdsWithCapability: Collection<String>): Boolean =
    nodeIdsWithCapability.isNotEmpty()
