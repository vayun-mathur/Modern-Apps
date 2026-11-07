package com.vayunmathur.everysync.data.sink

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import androidx.core.database.getStringOrNull
import com.vayunmathur.everysync.auth.AccountStore
import com.vayunmathur.everysync.data.RemoteEvent

/** A locally-edited event detected via the platform DIRTY/DELETED flags. */
data class LocalEventChange(
    val eventId: Long,
    val syncId: String?,
    val etag: String?,
    val deleted: Boolean,
    val event: RemoteEvent?,
)

/**
 * Creates one CalendarContract calendar per relay vault under the EverySync
 * account and writes events via caller-is-syncadapter URIs. The relay UID goes
 * in Events._SYNC_ID, the relay updatedAt stamp in SYNC_DATA1 and the vault id
 * in SYNC_DATA2.
 */
object CalendarSink {
    private const val TAG = "CalendarSink"

    /**
     * Health of the last [listLocal] read: false when the query failed or
     * returned a null cursor. Consulted by `CalendarRelayStore.listLocalOk`.
     */
    @Volatile var lastListLocalOk: Boolean = true
        private set
    private val ACCOUNT_TYPE = AccountStore.ACCOUNT_TYPE
    private const val DEFAULT_CALENDAR_COLOR = 0xFF3F51B5.toInt()
    private const val MILLIS_PER_SECOND = 1000L
    private const val CURSOR_INDEX_FIRST = 0
    private const val CURSOR_INDEX_SYNC_ID = 1
    private const val CURSOR_INDEX_ETAG = 2
    private const val CURSOR_INDEX_DELETED = 3
    private const val CURSOR_INDEX_TITLE = 4
    private const val CURSOR_INDEX_DESCRIPTION = 5
    private const val CURSOR_INDEX_LOCATION = 6
    private const val CURSOR_INDEX_DTSTART = 7
    private const val CURSOR_INDEX_DTEND = 8
    private const val CURSOR_INDEX_ALL_DAY = 9
    private const val CURSOR_INDEX_TIMEZONE = 10
    private const val CURSOR_INDEX_RRULE = 11

    private fun Uri.asSyncAdapter(accountName: String): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            .build()

    fun getOrCreateCalendarId(
        context: Context,
        accountName: String,
        remoteCalendarId: String,
        displayName: String,
        color: Int?,
    ): Long {
        val existing = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
                    "${CalendarContract.Calendars.NAME} = ?",
                arrayOf(accountName, ACCOUNT_TYPE, remoteCalendarId),
                "${CalendarContract.Calendars._ID} ASC",
            )?.use { while (it.moveToNext()) existing += it.getLong(CURSOR_INDEX_FIRST) }
        } catch (expected: Exception) {
            Log.error(TAG, "query calendar failed", expected)
        }
        if (existing.isNotEmpty()) {
            // Delete any duplicates created by earlier races; keep the first.
            existing.drop(1).forEach { dupId ->
                try {
                    context.contentResolver.delete(
                        CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName),
                        "${CalendarContract.Calendars._ID} = ?", arrayOf(dupId.toString()),
                    )
                } catch (expected: Exception) {
                    Log.error(TAG, "delete duplicate calendar failed", expected)
                }
            }
            return existing.first()
        }

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, accountName)
            put(CalendarContract.Calendars.NAME, remoteCalendarId)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, color ?: DEFAULT_CALENDAR_COLOR)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(
                CalendarContract.Calendars.CALENDAR_TIME_ZONE,
                java.util.TimeZone.getDefault().id,
            )
        }
        return try {
            context.contentResolver.insert(
                CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName), values,
            )?.lastPathSegment?.toLong() ?: -1L
        } catch (expected: Exception) {
            Log.error(TAG, "create calendar failed", expected)
            -1L
        }
    }

    fun localUidToEtag(
        context: Context,
        localCalendarId: Long,
    ): Map<String, String?> {
        val out = mutableMapOf<String, String?>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._SYNC_ID, CalendarContract.Events.SYNC_DATA1),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DELETED} = 0",
                arrayOf(localCalendarId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val uid = c.getStringOrNull(CURSOR_INDEX_FIRST) ?: continue
                    out[uid] = c.getStringOrNull(CURSOR_INDEX_SYNC_ID)
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "localUidToEtag failed", expected)
        }
        return out
    }

    /**
     * Every event in one calendar in relay form, for the merge's local
     * side — including DELETED rows as tombstones. Filtering DELETED rows
     * out (the old behaviour) made a local delete indistinguishable from
     * a missed read, so the merge resurrected the relay copy and the
     * delete never propagated. `updatedAt` comes from SYNC_DATA1 (the
     * relay stamp mirrors into `etag` on every relay write); rows that
     * predate relay sync carry no stamp, and rows with no _SYNC_ID are
     * bound to a freshly minted relay identity on this row (see below).
     */
    fun listLocal(context: Context, localCalendarId: Long): List<RemoteEvent> {
        val out = mutableListOf<RemoteEvent>()
        // Local column positions for the projection below (not the shared
        // CURSOR_INDEX_* constants, which map a different 12-column query).
        val colUid = 0
        val colStamp = 1
        val colTitle = 2
        val colDesc = 3
        val colLoc = 4
        val colStart = 5
        val colEnd = 6
        val colAllDay = 7
        val colTz = 8
        val colRrule = 9
        val colDeleted = 10
        val colId = 11
        var failed = false
        try {
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._SYNC_ID,
                    CalendarContract.Events.SYNC_DATA1,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.RRULE,
                    CalendarContract.Events.DELETED,
                    CalendarContract.Events._ID,
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(localCalendarId.toString()),
                null,
            )
            if (cursor == null) {
                // Null cursor = provider cannot serve: failure, not empty.
                failed = true
            } else {
                cursor.use { c ->
                    while (c.moveToNext()) {
                        var uid = c.getStringOrNull(colUid).orEmpty()
                        if (uid.isBlank()) {
                            // An event created on-device has no relay
                            // identity yet. Bind one to THIS row now: the
                            // merge can only mint an id in memory, and a
                            // later upsert-by-uid would insert a duplicate
                            // instead of attaching to this row, re-minting
                            // (and re-pushing) a copy every cycle.
                            uid = com.vayunmathur.everysync.sync.newRelaySyncId()
                            bindSyncId(context, localCalendarId, c.getLong(colId), uid)
                        }
                        out += RemoteEvent(
                            uid = uid,
                            etag = c.getStringOrNull(colStamp),
                            calendarId = localCalendarId.toString(),
                            summary = c.getStringOrNull(colTitle) ?: "",
                            description = c.getStringOrNull(colDesc) ?: "",
                            location = c.getStringOrNull(colLoc) ?: "",
                            startMillis = c.getLong(colStart),
                            endMillis = c.getLong(colEnd),
                            allDay = c.getInt(colAllDay) == 1,
                            timezone = c.getStringOrNull(colTz) ?: "UTC",
                            rrule = c.getStringOrNull(colRrule),
                            deleted = c.getInt(colDeleted) == 1,
                            updatedAt = c.getStringOrNull(colStamp)?.toLongOrNull() ?: 0L,
                        )
                    }
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "listLocal failed", expected)
            failed = true
        }
        lastListLocalOk = !failed
        return out
    }

    /** Attaches a freshly minted relay identity to an existing event row. */
    private fun bindSyncId(context: Context, localCalendarId: Long, eventId: Long, uid: String) {
        try {
            val accountName = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.ACCOUNT_NAME),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(localCalendarId.toString()),
                null,
            )?.use { if (it.moveToFirst()) it.getStringOrNull(0) else null } ?: return
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                ContentValues().apply { put(CalendarContract.Events._SYNC_ID, uid) },
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "bindSyncId failed", expected)
        }
    }

    private fun eventId(context: Context, localCalendarId: Long, uid: String): Long? =
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events._SYNC_ID} = ?",
                arrayOf(localCalendarId.toString(), uid),
                null,
            )?.use { if (it.moveToFirst()) it.getLong(CURSOR_INDEX_FIRST) else null }
        } catch (expected: Exception) {
            Log.error(TAG, "eventId failed", expected)
            null
        }

    fun upsertEvent(
        context: Context,
        accountName: String,
        localCalendarId: Long,
        event: RemoteEvent,
    ) {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, localCalendarId)
            put(CalendarContract.Events._SYNC_ID, event.uid)
            put(CalendarContract.Events.SYNC_DATA1, event.etag)
            put(CalendarContract.Events.SYNC_DATA2, event.href)
            put(CalendarContract.Events.TITLE, event.summary)
            put(CalendarContract.Events.DESCRIPTION, event.description)
            put(CalendarContract.Events.EVENT_LOCATION, event.location)
            put(CalendarContract.Events.DTSTART, event.startMillis)
            put(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (event.allDay) "UTC" else event.timezone,
            )
            put(CalendarContract.Events.DIRTY, 0)
            if (event.rrule.isNullOrBlank()) {
                put(CalendarContract.Events.DTEND, event.endMillis)
            } else {
                // Recurring events must use DURATION instead of DTEND.
                put(CalendarContract.Events.RRULE, event.rrule.removePrefix("RRULE:"))
                val durSecs = ((event.endMillis - event.startMillis) / MILLIS_PER_SECOND).coerceAtLeast(0)
                put(CalendarContract.Events.DURATION, "P${durSecs}S")
            }
        }
        try {
            val existing = eventId(context, localCalendarId, event.uid)
            if (existing != null) {
                context.contentResolver.update(
                    CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                    values, "${CalendarContract.Events._ID} = ?", arrayOf(existing.toString()),
                )
            } else {
                context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName), values,
                )
            }
        } catch (expected: Exception) {
            Log.error(TAG, "upsertEvent failed", expected)
        }
    }

    fun deleteEvent(context: Context, accountName: String, localCalendarId: Long, uid: String) {
        val id = eventId(context, localCalendarId, uid) ?: return
        try {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                "${CalendarContract.Events._ID} = ?", arrayOf(id.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "deleteEvent failed", expected)
        }
    }

    /**
     * Removes every calendar (and, by cascade, its events) for this account from
     * the on-device provider. Used when the user turns calendar sync off; the
     * account itself stays, so a later re-enable can repopulate it from a fresh
     * pull. Deleting via the caller-is-syncadapter URI hard-removes the rows.
     */
    fun purge(context: Context, accountName: String) {
        try {
            context.contentResolver.delete(
                CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(accountName),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "purge failed", expected)
        }
    }

    fun localCalendars(context: Context, accountName: String): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
                    "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(accountName, ACCOUNT_TYPE),
                null,
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(CURSOR_INDEX_FIRST) }
        } catch (expected: Exception) {
            Log.error(TAG, "localCalendars failed", expected)
        }
        return ids
    }

    fun getLocalChanges(
        context: Context,
        localCalendarId: Long,
    ): List<LocalEventChange> {
        val changes = mutableListOf<LocalEventChange>()
        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events._SYNC_ID,
                    CalendarContract.Events.SYNC_DATA1,
                    CalendarContract.Events.DELETED,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.EVENT_LOCATION,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.EVENT_TIMEZONE,
                    CalendarContract.Events.RRULE,
                ),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                    "(${CalendarContract.Events.DIRTY} = 1 OR ${CalendarContract.Events.DELETED} = 1)",
                arrayOf(localCalendarId.toString()),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    changes += readChange(c, localCalendarId)
                }
            }
        } catch (expected: Exception) {
            Log.error(TAG, "getLocalChanges failed", expected)
        }
        return changes
    }

    private fun readChange(
        c: android.database.Cursor,
        localCalendarId: Long,
    ): LocalEventChange {
        val deleted = c.getInt(CURSOR_INDEX_DELETED) == 1
        val uid = c.getStringOrNull(CURSOR_INDEX_SYNC_ID)
        return LocalEventChange(
            eventId = c.getLong(CURSOR_INDEX_FIRST),
            syncId = uid,
            etag = c.getStringOrNull(CURSOR_INDEX_ETAG),
            deleted = deleted,
            event = if (deleted || uid == null) {
                null
            } else {
                RemoteEvent(
                    uid = uid,
                    calendarId = localCalendarId.toString(),
                    summary = c.getStringOrNull(CURSOR_INDEX_TITLE) ?: "",
                    description = c.getStringOrNull(CURSOR_INDEX_DESCRIPTION) ?: "",
                    location = c.getStringOrNull(CURSOR_INDEX_LOCATION) ?: "",
                    startMillis = c.getLong(CURSOR_INDEX_DTSTART),
                    endMillis = c.getLong(CURSOR_INDEX_DTEND),
                    allDay = c.getInt(CURSOR_INDEX_ALL_DAY) == 1,
                    timezone = c.getStringOrNull(CURSOR_INDEX_TIMEZONE) ?: "UTC",
                    rrule = c.getStringOrNull(CURSOR_INDEX_RRULE),
                )
            },
        )
    }

    fun clearDirty(context: Context, accountName: String, eventId: Long) {
        try {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(accountName),
                ContentValues().apply { put(CalendarContract.Events.DIRTY, 0) },
                "${CalendarContract.Events._ID} = ?", arrayOf(eventId.toString()),
            )
        } catch (expected: Exception) {
            Log.error(TAG, "clearDirty failed", expected)
        }
    }
}
