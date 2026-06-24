package com.vayunmathur.calendar.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.absoluteValue

/**
 * Creates/removes a per-country local "holiday" calendar in the system
 * provider on demand. A calendar is only created when the user selects a
 * country (never pre-populated for every country), and removing a country
 * deletes its calendar (which cascades its events).
 *
 * Calendars are tagged via [CalendarContract.Calendars.NAME] = "holiday::<CODE>"
 * so they can be found and mapped back to a country.
 */
object HolidayCalendarManager {
    private const val ACCOUNT_NAME = "Holidays"
    private const val NAME_PREFIX = "holiday::"
    private const val DAY_MS = 86_400_000L

    private val COLORS = intArrayOf(
        0xFF1565C0.toInt(), 0xFF2E7D32.toInt(), 0xFFC62828.toInt(),
        0xFF6A1B9A.toInt(), 0xFFEF6C00.toInt(), 0xFF00838F.toInt(),
    )

    private fun syncUri(base: Uri): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    /** ISO codes of countries that currently have a holiday calendar. */
    fun addedCountryCodes(context: Context): Set<String> {
        val out = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars.NAME),
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} LIKE ?",
                arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, "$NAME_PREFIX%"),
                null,
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(CalendarContract.Calendars.NAME)
                while (c.moveToNext()) {
                    c.getString(nameIdx)?.takeIf { it.startsWith(NAME_PREFIX) }
                        ?.let { out += it.removePrefix(NAME_PREFIX) }
                }
            }
        }.onFailure { Log.e("HolidayCal", "addedCountryCodes failed", it) }
        return out
    }

    private fun calendarIdFor(context: Context, code: String): Long? {
        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND ${CalendarContract.Calendars.NAME} = ?",
                arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL, NAME_PREFIX + code),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }.getOrNull()
    }

    /** Add a read-only holiday calendar for [code] and bulk-insert its holidays. No-op if it already exists. */
    fun addCountry(context: Context, code: String, displayName: String) {
        if (calendarIdFor(context, code) != null) return
        val color = COLORS[code.hashCode().absoluteValue % COLORS.size]

        val calValues = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.NAME, NAME_PREFIX + code)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "$displayName holidays")
            put(CalendarContract.Calendars.CALENDAR_COLOR, color)
            // Read-only: the user shouldn't edit holiday entries.
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_READ)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC")
        }

        val calId = runCatching {
            context.contentResolver.insert(syncUri(CalendarContract.Calendars.CONTENT_URI), calValues)
                ?.let { ContentUris.parseId(it) }
        }.onFailure { Log.e("HolidayCal", "create calendar failed", it) }.getOrNull() ?: return

        val rows = HolidayData.holidays(context, code).mapNotNull { h ->
            val date = runCatching { LocalDate.parse(h.d) }.getOrNull() ?: return@mapNotNull null
            val startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, h.n)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, startMs + DAY_MS)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            }
        }.toTypedArray()

        if (rows.isNotEmpty()) {
            // Insert as sync adapter so the provider allows writing into a read-only calendar.
            runCatching {
                context.contentResolver.bulkInsert(syncUri(CalendarContract.Events.CONTENT_URI), rows)
            }.onFailure { Log.e("HolidayCal", "insert events failed", it) }
        }
    }

    /** Remove the holiday calendar for [code] (deleting its events). No-op if absent. */
    fun removeCountry(context: Context, code: String) {
        val calId = calendarIdFor(context, code) ?: return
        runCatching {
            context.contentResolver.delete(
                syncUri(CalendarContract.Calendars.CONTENT_URI),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calId.toString()),
            )
        }.onFailure { Log.e("HolidayCal", "remove calendar failed", it) }
    }
}
