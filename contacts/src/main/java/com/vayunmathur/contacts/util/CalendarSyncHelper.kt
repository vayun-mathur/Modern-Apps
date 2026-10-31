package com.vayunmathur.contacts.util

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.vayunmathur.library.log.Log
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.ALL_DAY_EVENT_TIMEZONE
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.allDayEventEndMillis
import com.vayunmathur.contacts.data.allDayEventStartMillis
import com.vayunmathur.contacts.data.hasYear
import com.vayunmathur.library.ui.R as UiR
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.vayunmathur.contacts.data.Event as ContactEvent

object CalendarSyncHelper {
    private const val ACCOUNT_NAME = "Contacts"
    private const val ACCOUNT_TYPE = "com.vayunmathur.contacts"
    private const val CALENDAR_NAME = "Contacts"
    private const val CALENDAR_COLOR_ARGB = 0xFF4285F4
    private const val NO_YEAR_ANCHOR_YEAR = 2000
    
    private val syncMutex = Mutex()

    /** Appends the sync-adapter query params used by every calendar/event write here. */
    private fun Uri.asSyncAdapter(accountName: String, accountType: String): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()

    private fun ensureAccountExists(context: Context) {
        val accountManager = AccountManager.get(context)
        val accounts = accountManager.getAccountsByType(ACCOUNT_TYPE)
        if (accounts.isEmpty()) {
            val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
            accountManager.addAccountExplicitly(account, null, null)
        }
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    fun getOrCreateCalendarId(context: Context): Long {
        ensureAccountExists(context)
        deleteLegacyLocalCalendar(context)

        val calendarIds = queryCalendarIds(context)
        val syncAdapterUri =
            CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)

        if (calendarIds.isNotEmpty()) {
            calendarIds.drop(1).forEach { extraId ->
                deleteCalendarById(context, syncAdapterUri, extraId)
            }
            return calendarIds[0]
        }

        return insertCalendar(context, syncAdapterUri)
    }

    // Cleanup old local calendar if it exists to prevent duplicates from migration.
    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    private fun deleteLegacyLocalCalendar(context: Context) {
        try {
            val oldUri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(
                ACCOUNT_NAME,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            context.contentResolver.delete(oldUri, null, null)
        } catch (_: Exception) {
        }
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    private fun queryCalendarIds(context: Context): List<Long> {
        val calendarIds = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.ACCOUNT_NAME} = ? " +
                    "AND ${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
                arrayOf(ACCOUNT_NAME, ACCOUNT_TYPE),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    calendarIds.add(cursor.getLong(0))
                }
            }
        } catch (e: android.database.SQLException) {
            Log.error("CalendarSyncHelper", "Error querying calendar", e)
        } catch (e: SecurityException) {
            Log.error("CalendarSyncHelper", "Error querying calendar", e)
        } catch (e: IllegalArgumentException) {
            Log.error("CalendarSyncHelper", "Error querying calendar", e)
        } catch (e: Exception) {
            Log.error("CalendarSyncHelper", "Error querying calendar", e)
        }
        return calendarIds
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    private fun insertCalendar(context: Context, syncAdapterUri: Uri): Long {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                context.getString(UiR.string.contacts)
            )
            put(CalendarContract.Calendars.NAME, CALENDAR_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, CALENDAR_COLOR_ARGB.toInt())
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_READ
            )
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            // UTC: every event this calendar holds is an all-day birthday or
            // anniversary stored at UTC midnight, so the calendar zone stays
            // fixed instead of tracking the device zone across DST changes.
            put(
                CalendarContract.Calendars.CALENDAR_TIME_ZONE,
                ALL_DAY_EVENT_TIMEZONE
            )
        }

        return try {
            val newUri = context.contentResolver.insert(syncAdapterUri, values)
            newUri?.lastPathSegment?.toLong() ?: -1L
        } catch (e: android.content.OperationApplicationException) {
            Log.error("CalendarSyncHelper", "Error creating calendar", e)
            -1L
        } catch (e: android.os.RemoteException) {
            Log.error("CalendarSyncHelper", "Error creating calendar", e)
            -1L
        } catch (e: SecurityException) {
            Log.error("CalendarSyncHelper", "Error creating calendar", e)
            -1L
        } catch (e: IllegalArgumentException) {
            Log.error("CalendarSyncHelper", "Error creating calendar", e)
            -1L
        }
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    private fun deleteCalendarById(context: Context, syncAdapterUri: Uri, calendarId: Long) {
        try {
            context.contentResolver.delete(
                syncAdapterUri,
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(calendarId.toString())
            )
        } catch (_: Exception) {
        }
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun syncContact(context: Context, contact: Contact) {
        syncMutex.withLock {
            val calendarId = getOrCreateCalendarId(context)
            if (calendarId == -1L) return

            val eventUri =
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)

            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newDelete(eventUri)
                    .withSelection(
                        "${CalendarContract.Events.CALENDAR_ID} = ? " +
                            "AND ${CalendarContract.Events.SYNC_DATA1} = ?",
                        arrayOf(calendarId.toString(), contact.id.toString())
                    )
                    .build()
            )

            contact.details.dates.forEach { dateEvent ->
                if (isCalendarDateType(dateEvent.type)) {
                    ops.addAll(
                        buildAddEventOperations(context, calendarId, contact, dateEvent, eventUri)
                    )
                }
            }

            try {
                if (ops.isNotEmpty()) {
                    context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                }
            } catch (e: android.content.OperationApplicationException) {
                Log.error("CalendarSyncHelper", "Error applying batch sync for contact", e)
            } catch (e: android.os.RemoteException) {
                Log.error("CalendarSyncHelper", "Error applying batch sync for contact", e)
            } catch (e: SecurityException) {
                Log.error("CalendarSyncHelper", "Error applying batch sync for contact", e)
            } catch (e: IllegalArgumentException) {
                Log.error("CalendarSyncHelper", "Error applying batch sync for contact", e)
            }
        }
    }

    private fun isCalendarDateType(type: Int): Boolean =
        type == CDKEvent.TYPE_BIRTHDAY || type == CDKEvent.TYPE_ANNIVERSARY

    private fun buildAddEventOperations(
        context: Context,
        calendarId: Long,
        contact: Contact,
        dateEvent: ContactEvent,
        eventUri: android.net.Uri
    ): List<ContentProviderOperation> {
        val originalDate = dateEvent.startDate

        val eventTypeStr = if (dateEvent.type == CDKEvent.TYPE_BIRTHDAY) {
            context.getString(R.string.birthday)
        } else {
            context.getString(R.string.anniversary)
        }

        // Title deliberately omits age: a single FREQ=YEARLY instance is reused every
        // year, so any embedded age would go stale after the first occurrence.
        val title = "${contact.name.value}: $eventTypeStr"

        val hasYear = originalDate.hasYear
        // RRULE makes the DTSTART year irrelevant (only month/day recur), so for
        // no-year dates (year 1604 sentinel) normalize to a leap year (2000). This
        // ensures Feb-29 no-year birthdays still recur instead of collapsing to an
        // invalid date. For dates with a real year, keep the original year so the
        // first instance lands correctly; Feb 29 then fires on leap years only.
        // NOTE: a yearly RRULE starting Feb 29 fires only on leap years on most
        // providers — accepted as correct rather than shifting to Feb 28, which
        // would show the birthday on the wrong day in leap years.
        val anchorYear = if (hasYear) originalDate.year else NO_YEAR_ANCHOR_YEAR
        val anchorDate = try {
            LocalDate(anchorYear, originalDate.month, originalDate.day)
        } catch (_: IllegalArgumentException) {
            return emptyList()
        }

        // All-day rows must sit on UTC midnight with EVENT_TIMEZONE "UTC":
        // the provider treats them as timezone-independent wall dates only
        // in that form. Anchoring at local midnight instead bakes the sync
        // moment's UTC offset into DTSTART, and expanding the yearly RRULE
        // under a different DST regime slides the instance a day early
        // (birthdays in April-October shifted for US/EU zones). Converting
        // through UTC keeps the instant identical year-round.
        val startMillis = anchorDate.allDayEventStartMillis()

        return listOf(
            ContentProviderOperation.newInsert(eventUri)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, title)
                .withValue(CalendarContract.Events.DTSTART, startMillis)
                .withValue(CalendarContract.Events.DTEND, allDayEventEndMillis(startMillis))
                .withValue(CalendarContract.Events.ALL_DAY, 1)
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, ALL_DAY_EVENT_TIMEZONE)
                .withValue(CalendarContract.Events.RRULE, "FREQ=YEARLY")
                .withValue(CalendarContract.Events.SYNC_DATA1, contact.id.toString())
                .build()
        )
    }
    
    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun syncAll(context: Context) {
        syncMutex.withLock {
            val calendarId = getOrCreateCalendarId(context)
            if (calendarId == -1L) return

            val eventUri =
                CalendarContract.Events.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)

            try {
                context.contentResolver.delete(
                    eventUri,
                    "${CalendarContract.Events.CALENDAR_ID} = ?",
                    arrayOf(calendarId.toString())
                )
            } catch (e: android.database.SQLException) {
                Log.error("CalendarSyncHelper", "Error clearing calendar", e)
            } catch (e: SecurityException) {
                Log.error("CalendarSyncHelper", "Error clearing calendar", e)
            } catch (e: IllegalArgumentException) {
                Log.error("CalendarSyncHelper", "Error clearing calendar", e)
            }

            val contacts = Contact.getAllContacts(context)
            for (contact in contacts) {
                syncSingleContactInAll(context, calendarId, contact, eventUri)
            }
        }
    }

    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    private fun syncSingleContactInAll(
        context: Context,
        calendarId: Long,
        contact: Contact,
        eventUri: Uri,
    ) {
        val ops = ArrayList<ContentProviderOperation>()
        contact.details.dates.forEach { dateEvent ->
            if (isCalendarDateType(dateEvent.type)) {
                ops.addAll(
                    buildAddEventOperations(context, calendarId, contact, dateEvent, eventUri)
                )
            }
        }
        if (ops.isEmpty()) return
        try {
            context.contentResolver.applyBatch(CalendarContract.AUTHORITY, ops)
        } catch (e: android.content.OperationApplicationException) {
            Log.error("CalendarSyncHelper", "Error in syncAll batch", e)
        } catch (e: android.os.RemoteException) {
            Log.error("CalendarSyncHelper", "Error in syncAll batch", e)
        } catch (e: SecurityException) {
            Log.error("CalendarSyncHelper", "Error in syncAll batch", e)
        } catch (e: IllegalArgumentException) {
            Log.error("CalendarSyncHelper", "Error in syncAll batch", e)
        }
    }
    
    // Broad catch is deliberate: calendar providers vary by OEM and any
    // failure here must not block calendar creation.
    @Suppress("TooGenericExceptionCaught")
    suspend fun removeCalendar(context: Context) {
        syncMutex.withLock {
            val uri =
                CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(ACCOUNT_NAME, ACCOUNT_TYPE)
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: android.database.SQLException) {
                Log.error("CalendarSyncHelper", "Error removing calendar", e)
            } catch (e: SecurityException) {
                Log.error("CalendarSyncHelper", "Error removing calendar", e)
            } catch (e: IllegalArgumentException) {
                Log.error("CalendarSyncHelper", "Error removing calendar", e)
            }

            try {
                val oldUri = CalendarContract.Calendars.CONTENT_URI.asSyncAdapter(
                    ACCOUNT_NAME,
                    CalendarContract.ACCOUNT_TYPE_LOCAL
                )
                context.contentResolver.delete(oldUri, null, null)
            } catch (_: Exception) {
            }
        }
    }
}
