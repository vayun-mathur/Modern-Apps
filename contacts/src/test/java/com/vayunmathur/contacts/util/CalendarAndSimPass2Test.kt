package com.vayunmathur.contacts.util

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContact
import com.vayunmathur.contacts.data.SimContactsDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** Second-pass coverage for calendar sync and the SIM data source (no SIM/calendar on Robolectric). */
@RunWith(RobolectricTestRunner::class)
class CalendarAndSimPass2Test {

    /** Empty calendar provider: every query returns no rows, writes succeed with synthetic ids. */
    class FakeCalendarProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(projection ?: arrayOf(CalendarContract.Calendars._ID))

        private var nextId = 500L
        override fun insert(uri: Uri, values: ContentValues?): Uri = ContentUris.withAppendedId(uri, nextId++)
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 1
        override fun getType(uri: Uri): String? = null
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun contact(dates: List<Event>): Contact = Contact(
        id = 7, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE)),
            emails = emptyList(), addresses = emptyList(), dates = dates, photos = emptyList(),
            names = listOf(Name(2, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(3, "")),
            notes = listOf(Note(4, "")),
            nicknames = listOf(Nickname(5, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun calendarSyncAgainstEmptyProvider() {
        ShadowContentResolver.registerProviderInternal(
            CalendarContract.AUTHORITY, FakeCalendarProvider()
        )
        val withBirthday = contact(
            listOf(
                Event(1, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY),
                Event(2, LocalDate(2020, 6, 1), CDKEvent.TYPE_ANNIVERSARY),
            )
        )
        runBlocking {
            CalendarSyncHelper.syncContact(ctx, withBirthday)
            CalendarSyncHelper.syncContact(ctx, contact(emptyList()))
            CalendarSyncHelper.syncAll(ctx)
            CalendarSyncHelper.removeCalendar(ctx)
        }
        // reaching here without throwing is the behavior: empty-provider sync degrades cleanly
        assertEquals("Aisha Rahman", withBirthday.name.value)
    }

    @Test
    fun calendarSyncWithoutProviderRegistered() {
        // no calendar provider at all: queries return null and helpers must not crash
        runBlocking {
            CalendarSyncHelper.syncAll(ctx)
            CalendarSyncHelper.removeCalendar(ctx)
        }
    }

    @Test
    fun simDataSourceWithoutSims() {
        assertTrue(SimContactsDataSource.getSimSubscriptionInfos(ctx).isEmpty())
        assertTrue(SimContactsDataSource.listSimContacts(ctx).isEmpty())
        assertTrue(SimContactsDataSource.simContactsAsContacts(ctx).isEmpty())
        assertEquals(
            null,
            SimContactsDataSource.findBackingSimContact(
                ctx,
                contact(emptyList()).copy(accountType = "com.google"),
            ),
        )
    }

    @Test
    fun simInfoHelpersFormatLabels() {
        val info = SimContactsDataSource.SimSubscriptionInfo(
            subscriptionId = 5, slotIndex = 1, displayName = "My SIM", carrierName = "T-Mobile",
        )
        assertEquals(2, SimContactsDataSource.slotNumberFor(info))
        assertEquals("5", SimContactsDataSource.accountNameFor(info))
        val label = SimContactsDataSource.getSimAccountDisplayLabel(ctx, info)
        assertTrue(label.contains("T-Mobile"), label)
        assertTrue(label.contains("2"), label)

        val noCarrier = SimContactsDataSource.SimSubscriptionInfo(3, -1, null, null)
        assertEquals(1, SimContactsDataSource.slotNumberFor(noCarrier))
        val plainLabel = SimContactsDataSource.getSimAccountDisplayLabel(ctx, noCarrier)
        assertTrue(plainLabel.isNotBlank())

        assertEquals("5", SimContactsDataSource.accountNameForSimContact(
            SimContact(name = "A", number = "1", subscriptionId = 5)
        ))
        assertEquals("", SimContactsDataSource.accountNameForSimContact(
            SimContact(name = "A", number = "1", subscriptionId = null)
        ))
        assertEquals(SIM_ACCOUNT_TYPE, "com.vayunmathur.contacts.sim")
    }

    @Test
    fun simContactDeleteAttemptsAdnDelete() {
        // with a subscription id the delete goes straight at that SIM's ADN URI;
        // the shadow resolver reports a deleted row
        assertEquals(
            true,
            SimContactsDataSource.deleteSimContact(
                ctx, SimContact(name = "A", number = "+1415", subscriptionId = 5)
            ),
        )
    }

    @Test
    fun packageUtilsInstallationChecks() {
        // Robolectric's package manager has none of the messaging apps installed
        assertEquals(false, PackageUtils.isWhatsAppInstalled(ctx))
        assertEquals(false, PackageUtils.isSignalInstalled(ctx))
        assertEquals(false, PackageUtils.isTelegramInstalled(ctx))
        assertEquals(false, PackageUtils.isGoogleMeetInstalled(ctx))
        assertEquals(false, PackageUtils.isAppInstalled(ctx, "com.example.nonexistent"))
        // the contacts app itself IS installed in the test environment
        assertEquals(true, PackageUtils.isAppInstalled(ctx, ctx.packageName))
        assertNull(
            // no aggregate row in an empty provider: platforms come back empty rather than crashing
            PackageUtils.getContactPlatforms(ctx, 7L).let { platforms ->
                platforms.whatsAppMessageId ?: platforms.signalMessageId ?: platforms.telegramMessageId
            },
        )
    }
}
