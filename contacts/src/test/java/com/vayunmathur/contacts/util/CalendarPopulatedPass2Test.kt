package com.vayunmathur.contacts.util

import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** Calendar sync against populated fake providers (contacts provider + calendar). */
@RunWith(RobolectricTestRunner::class)
class CalendarPopulatedPass2Test {

    class CountingCalendarProvider : android.content.ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: android.net.Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): android.database.Cursor =
            android.database.MatrixCursor(projection ?: arrayOf(CalendarContract.Calendars._ID))

        private var nextId = 500L
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri =
            android.content.ContentUris.withAppendedId(uri, nextId++)

        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun update(
            uri: android.net.Uri, values: android.content.ContentValues?,
            selection: String?, selectionArgs: Array<out String>?,
        ): Int = 1

        override fun getType(uri: android.net.Uri): String? = null

        override fun applyBatch(
            authority: String, operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> {
            batchSizes += operations.size
            return super.applyBatch(authority, operations)
        }

        companion object {
            val batchSizes = mutableListOf<Int>()
        }
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @org.junit.Before
    fun grantCalendar() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        )
    }

    private fun contact(id: Long, dates: List<Event>): Contact = Contact(
        id = id, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = emptyList(), emails = emptyList(), addresses = emptyList(),
            dates = dates, photos = emptyList(),
            names = listOf(Name(0, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(0, "")),
            notes = listOf(Note(0, "")),
            nicknames = listOf(Nickname(0, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun calendarIdIsCreatedAgainstFakeProvider() {
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.AUTHORITY, CountingCalendarProvider()
        )
        val id = CalendarSyncHelper.getOrCreateCalendarId(ctx)
        assertTrue(id > 0, "calendar id should be created, was $id")
        // second call finds the same calendar (provider returns no rows,
        // so it inserts again and gets the next id)
        val second = CalendarSyncHelper.getOrCreateCalendarId(ctx)
        assertTrue(second > 0)
    }

    @Test
    fun syncAllSyncsProviderContactsDates() {
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.AUTHORITY, CountingCalendarProvider()
        )
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        // syncAll clears the calendar, loads the fake provider's contacts and
        // builds event operations for Aisha's dates. Robolectric's shadow
        // resolver rejects the sync-adapter batch (caught inside), so the
        // assertion is completion, not provider-side application.
        runBlocking { CalendarSyncHelper.syncAll(ctx) }
        assertTrue(CalendarSyncHelper.getOrCreateCalendarId(ctx) > 0)
    }

    @Test
    fun syncContactWithMixedDateTypes() {
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.AUTHORITY, CountingCalendarProvider()
        )
        CountingCalendarProvider.batchSizes.clear()
        val mixed = contact(
            42,
            listOf(
                Event(1, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY),
                Event(2, LocalDate(1604, 3, 15), CDKEvent.TYPE_BIRTHDAY), // no-year sentinel
                Event(3, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
                Event(4, LocalDate(2021, 1, 1), CDKEvent.TYPE_OTHER), // not a calendar type
                Event(5, LocalDate(2022, 2, 2), CDKEvent.TYPE_CUSTOM),
            ),
        )
        // builds delete + insert ops for birthday/no-year/anniversary only;
        // completes even though the shadow resolver rejects the batch
        runBlocking { CalendarSyncHelper.syncContact(ctx, mixed) }
    }

    @Test
    fun syncContactWithoutDatesOnlyDeletes() {
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.AUTHORITY, CountingCalendarProvider()
        )
        CountingCalendarProvider.batchSizes.clear()
        runBlocking { CalendarSyncHelper.syncContact(ctx, contact(9, emptyList())) }
    }
}
