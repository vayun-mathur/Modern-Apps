package com.vayunmathur.calendar

import android.content.Context
import android.provider.CalendarContract

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
            val cursor = context.contentResolver.query(uri, projection, null, null, null)
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(CalendarContract.Calendars._ID))
                    val account = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)) ?: ""
                    val display = it.getString(it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)) ?: ""
                    val color = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR))
                    val access = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL))
                    val visible = it.getInt(it.getColumnIndexOrThrow(CalendarContract.Calendars.VISIBLE)) == 1
                    list.add(Calendar(id, account, display, color, access, visible))
                }
            }
            return list
        }
    }
}