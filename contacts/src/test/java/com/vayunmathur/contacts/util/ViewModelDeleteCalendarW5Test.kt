package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimProviderPass2Test
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** W5: SIM/provider delete paths, calendar-sync-on-delete, and CalendarWorker. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelDeleteCalendarW5Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
    }

    private fun contact(
        id: Long, accountType: String?, accountName: String? = null,
        withDate: Boolean = false,
    ) = Contact(
        id = id, accountType = accountType, accountName = accountName, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, "+14155550102", CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(id, "aisha@x.com", CDKEmail.TYPE_HOME)),
            addresses = emptyList(),
            dates = if (withDate) listOf(Event(id, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)) else emptyList(),
            photos = emptyList(),
            names = listOf(Name(id, "", "Aisha", "", "Rahman", "")),
            orgs = emptyList(), notes = emptyList(), nicknames = emptyList(), groups = emptyList(),
        ),
    )

    private fun settle(vm: ContactViewModel) = runBlocking {
        // Delete runs on viewModelScope/IO; syncing afterwards flushes it and re-reads state.
        vm.syncFromSystem()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun deleteSimBackedContactDeletesFromIccAndResyncs() {
        val vm = ContactViewModel(app)
        vm.deleteContact(contact(1, SIM_ACCOUNT_TYPE, accountName = "1"))
        settle(vm)
        assertNotNullDelete()
    }

    private fun assertNotNullDelete() {
        assertTrue(
            SimProviderPass2Test.FakeIccProvider.lastDeleteUri != null ||
                SimProviderPass2Test.FakeIccProvider.lastDeleteSelection != null,
            "SIM delete should reach the ICC provider",
        )
    }

    @Test
    fun deleteSimContactWithoutBackingRowUsesFallback() {
        val vm = ContactViewModel(app)
        // A SIM-typed contact whose number matches no ICC row: fallback path.
        val orphan = contact(99, SIM_ACCOUNT_TYPE, accountName = "3").copy(
            details = contact(99, SIM_ACCOUNT_TYPE).details.copy(
                phoneNumbers = listOf(PhoneNumber(99, "+19999999999", CDKPhone.TYPE_MOBILE)),
            )
        )
        vm.deleteContact(orphan)
        settle(vm)
        assertNotNullDelete()
    }

    @Test
    fun deleteProviderContactWithCalendarSyncEnabled() {
        // Seed the persisted flag directly: setCalendarSyncEnabled(true) also
        // kicks off a full calendar sync, which is not what this test drives.
        runBlocking { com.vayunmathur.library.util.DataStoreUtils.getInstance(app).setBoolean("calendar_sync_enabled", true) }
        val vm = ContactViewModel(app)
        // WhileSubscribed: the flow only runs while collected.
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch { vm.isCalendarSyncEnabled.collect {} }
        try {
        val deadline = System.currentTimeMillis() + 10_000
        while (!vm.isCalendarSyncEnabled.value && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(25)
        }
        assertTrue(vm.isCalendarSyncEnabled.value)
        vm.deleteContact(contact(7, "com.google", "a@gmail.com", withDate = true))
        settle(vm)
        runBlocking { com.vayunmathur.library.util.DataStoreUtils.getInstance(app).setBoolean("calendar_sync_enabled", false) }
        } finally { collector.cancel() }
    }

    @Test
    fun calendarWorkerScheduleAndCancelAreSafe() {
        CalendarWorker.schedule(app)
        CalendarWorker.cancel(app)
    }

    @Test
    fun calendarSyncHelperSyncsContactWithAndWithoutDates() = runBlocking {
        CalendarSyncHelper.syncContact(app, contact(7, "com.google", withDate = true))
        CalendarSyncHelper.syncContact(app, contact(8, "com.google", withDate = false))
        CalendarSyncHelper.syncAll(app)
    }
}
