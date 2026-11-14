package com.vayunmathur.calendar.data
import android.content.Context
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log

/** An on-device calendar from the calendar provider: identity, display colour, access level, and visibility. */
data class Calendar(
    val id: Long,
    val accountName: String,
    val displayName: String,
    val color: Int,
    val accessLevel: Int,
    val visible: Boolean
) {
    val canModify: Boolean
        get() = accessLevel >= CalendarContract.Calendars.CAL_ACCESS_EDITOR

    /** Provider queries that load [Calendar] rows. */
    companion object {
        fun getAllCalendars(context: Context): List<Calendar> {
            val list = mutableListOf<Calendar>()
            val uri = CalendarContract.Calendars.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.VISIBLE
            )
            try {
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    while (it.moveToNext()) {
                        try {
                            list.add(readCalendarRow(it))
                        } catch (expected: Exception) {
                            Log.error("Calendar", "Error constructing calendar from cursor", expected)
                        }
                    }
                }
            } catch (expected: Exception) {
                Log.error("Calendar", "Error querying calendars", expected)
            }
            return list
        }

        private fun readCalendarRow(c: android.database.Cursor): Calendar {
            val id = c.getLong(c.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
            val account = c.getString(
                c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME),
            ) ?: ""
            val display = c.getString(
                c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            ) ?: ""
            val color = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR))
            val access = c.getInt(
                c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            )
            val visible = c.getInt(c.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)) == 1
            return Calendar(id, account, display, color, access, visible)
        }
    }
}
