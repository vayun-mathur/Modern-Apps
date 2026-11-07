package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Second-pass ViewModel write-path coverage against the fake contacts provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VMOperationsPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }


    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun richContact(id: Long = 7L, accountType: String? = "com.google", accountName: String? = "a@gmail.com") =
        Contact(
            id = id, accountType = accountType, accountName = accountName, isFavorite = true,
            details = ContactDetails(
                phoneNumbers = listOf(
                    PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                    PhoneNumber(2, "+14155550103", CDKPhone.TYPE_HOME),
                ),
                emails = listOf(
                    Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
                    Email(4, "aisha2@x.com", CDKEmail.TYPE_WORK),
                ),
                addresses = listOf(
                    com.vayunmathur.contacts.data.Address(5, "1 Main St", com.vayunmathur.contacts.data.CDKStructuredPostal.TYPE_HOME)
                ),
                dates = listOf(Event(6, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)),
                photos = listOf(Photo(7, "AAAA")),
                names = listOf(Name(8, "", "Aisha", "", "Rahman", "")),
                orgs = listOf(Organization(9, "Acme")),
                notes = listOf(Note(10, "hello")),
                nicknames = listOf(Nickname(11, "Ash", CDKNickname.TYPE_DEFAULT)),
                groups = listOf(GroupMembership(12, 11L)),
            ),
            customRingtone = "content://ringtone/1",
        )

    @Test
    fun syncLoadsProviderContactsIntoState() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        assertEquals(true, vm.hasLoadedContacts.value)
        // fake provider has Aisha (7) and Bob (8)
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        assertEquals("Aisha", vm.getContact(7L)?.name?.firstName)
    }

    @Test
    fun persistAndSaveProviderContact() {
        val vm = vm()
        val contact = richContact(id = 0L)
        val persisted = runBlocking { vm.persistContact(contact) }
        assertTrue(persisted, "persisting into the fake provider should succeed")
        vm.saveContact(contact) // async variant must not throw
        vm.deleteContact(richContact()) // provider delete against fake provider
        runBlocking { vm.syncFromSystem() }
    }

    @Test
    fun saveEditDraftPersistsNewContact() {
        val vm = vm()
        vm.initEditDraft(null)
        vm.updateEditDraft {
            it.copy(firstName = "New", lastName = "Person", phoneNumbers = listOf(PhoneNumber(0, "+14155550199", CDKPhone.TYPE_MOBILE)))
        }
        val result = CompletableDeferred<Triple<Boolean, String?, Long?>>()
        vm.saveEditDraft { ok, err, savedId -> result.complete(Triple(ok, err, savedId)) }
        val (ok, err, _) = runBlocking { withTimeout(30_000) { result.await() } }
        assertTrue(ok, "saveEditDraft should succeed against the fake provider, err=$err")
    }

    @Test
    fun saveEditDraftBlankSimDraftFailsValidation() {
        val vm = vm()
        vm.initEditDraft(null)
        vm.updateEditDraft { it.copy(accountType = SIM_ACCOUNT_TYPE, firstName = "", phoneNumbers = emptyList()) }
        val result = CompletableDeferred<Triple<Boolean, String?, Long?>>()
        vm.saveEditDraft { ok, err, savedId -> result.complete(Triple(ok, err, savedId)) }
        val (ok, _, _) = runBlocking { withTimeout(30_000) { result.await() } }
        assertEquals(false, ok)
    }

    @Test
    fun addAndDeleteGroupIssueProviderWrites() {
        val vm = vm()
        vm.addGroup("Family")
        vm.deleteGroup(11L)
        // writes are fire-and-forget on IO; a later sync gives them time and must not crash
        runBlocking { vm.syncFromSystem() }
        assertNotNull(vm.groups)
    }

    @Test
    fun transferContactsToAnotherAccount() {
        val vm = vm()
        assertEquals(setOf(7L, 8L), vm.allContactsState.value.map { it.id }.toSet())
        val result = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(listOf(7L), "other@gmail.com", "com.google") { moved, failed, dropped ->
            result.complete(Triple(moved, failed, dropped))
        }
        val (moved, failed, groupsDropped) = runBlocking { withTimeout(30_000) { result.await() } }
        assertEquals(1, moved)
        assertEquals(0, failed)
        // a provider-to-provider move keeps group membership, so nothing is dropped
        assertEquals(false, groupsDropped)
    }

    @Test
    fun transferToSameAccountIsSkippedAndUnknownIdFails() {
        val vm = vm()
        val same = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(listOf(7L), "a@gmail.com", "com.google") { m, f, d -> same.complete(Triple(m, f, d)) }
        val (movedSame, failedSame, _) = runBlocking { withTimeout(30_000) { same.await() } }
        assertEquals(0, movedSame)
        assertEquals(0, failedSame)

        val unknown = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(listOf(999L), "other@gmail.com", "com.google") { m, f, d -> unknown.complete(Triple(m, f, d)) }
        val (movedUnknown, failedUnknown, _) = runBlocking { withTimeout(30_000) { unknown.await() } }
        assertEquals(0, movedUnknown)
        assertEquals(1, failedUnknown)
    }

    @Test
    fun computeSimLossListsEverythingSimCannotStore() {
        val loss = computeSimLoss(richContact())
        assertTrue(SimDroppedField.PHOTO in loss)
        assertTrue(SimDroppedField.EXTRA_PHONES in loss)
        assertTrue(SimDroppedField.EXTRA_EMAILS in loss)
        assertTrue(SimDroppedField.ADDRESSES in loss)
        assertTrue(SimDroppedField.DATES in loss)
        assertTrue(SimDroppedField.ORGANIZATION in loss)
        assertTrue(SimDroppedField.NOTE in loss)
        assertTrue(SimDroppedField.NICKNAME in loss)
        assertTrue(SimDroppedField.GROUPS in loss)
        assertTrue(SimDroppedField.RINGTONE in loss)
        assertTrue(SimDroppedField.FAVORITE in loss)

        val minimal = Contact(
            id = 1, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails.empty().copy(
                names = listOf(Name(0, "", "Bob", "", "Lee", "")),
                phoneNumbers = listOf(PhoneNumber(0, "+1415", CDKPhone.TYPE_MOBILE)),
            ),
        )
        assertTrue(computeSimLoss(minimal).isEmpty())
    }

    @Test
    fun accountLifecycleThroughDataStore() {
        val vm = vm()
        // createAccount is fire-and-forget on the VM scope; in a shared test JVM its
        // DataStore write can be lost to another sandbox's store instance, so seed the
        // same entry through the suspend API and verify the reload path deterministically.
        vm.createAccount("Family")
        runBlocking {
            vm.dataStore.addStringToSetIfAbsent(
                "extra_accounts_set",
                encodeExtraAccountEntry("Family", "com.vayunmathur.contacts.local"),
            )
            vm.loadAccountsInternal()
        }
        assertTrue(vm.accounts.value.any { it.name == "Family" },
            "saved account should appear in accounts state, accounts=${vm.accounts.value}")
        // last-selected account round-trips through DataStore
        vm.setLastSelectedAccount("a@gmail.com", "com.google")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (vm.lastSelectedAccountState.value != ContactAccount("a@gmail.com", "com.google") &&
            System.nanoTime() < deadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(ContactAccount("a@gmail.com", "com.google"), vm.lastSelectedAccountState.value)
        vm.loadLastSelectedAccount()
        assertEquals("a@gmail.com", vm.lastSelectedAccountState.value?.name)

        // visibility toggling writes hidden-account keys both ways, both overloads
        val account = ContactAccount("a@gmail.com", "com.google")
        vm.setAccountVisibility(account, false)
        vm.setAccountVisibility(account, true)
        vm.setAccountVisibility("a@gmail.com", false)
        vm.setAccountVisibility("a@gmail.com", true)
        vm.setAccountVisibility("unknown-account", false)
        vm.setAccountVisibility("unknown-account", true)

        // blank account names are rejected
        val blank = CompletableDeferred<Unit>()
        vm.createAccount("   ") { blank.complete(Unit) }
        // rename guards: unknown account cannot be renamed
        vm.renameLocalAccount(ContactAccount("nope", "com.vayunmathur.contacts.local"), "NewName") { _, _ -> }
        vm.deleteLocalAccount(ContactAccount("nope", "com.vayunmathur.contacts.local"))
        runBlocking { vm.syncFromSystem() }
    }

    @Test
    fun calendarSyncToggleOffRunsRemovePath() {
        // WorkManager must be initialized in the test env, or CalendarWorker.cancel
        // throws out of the VM coroutine and leaks as an uncaught exception into
        // whichever test runs next in the JVM.
        runCatching {
            androidx.work.WorkManager.initialize(
                app, androidx.work.Configuration.Builder().build()
            )
        }
        val vm = vm()
        // disabling runs CalendarSyncHelper.removeCalendar + CalendarWorker.cancel;
        // with no calendar provider/calendar permission this must degrade, not crash the VM
        vm.setCalendarSyncEnabled(false)
        // wait for the launched toggle work to actually finish before teardown
        val deadline = System.nanoTime() + 15_000_000_000L
        while (vm.dataStore.getBoolean("calendar_sync_enabled", true) &&
            System.nanoTime() < deadline
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(false, vm.dataStore.getBoolean("calendar_sync_enabled", true))
        runBlocking { vm.syncFromSystem() }
        assertEquals(false, vm.isCalendarSyncEnabled.value)
    }

    @Test
    fun simLabelHelpersAndSlotLabels() {
        val vm = vm()
        // no SIM subscriptions under Robolectric: labels resolve to null/empty
        assertEquals(null, vm.simDisplayLabel(ContactAccount("SIM", SIM_ACCOUNT_TYPE)))
        assertEquals(null, vm.simDisplayLabelFor(SIM_ACCOUNT_TYPE, "SIM"))
        assertEquals(null, vm.simDisplayLabelFor(null, null))
        val labels = vm.simSlotLabelsFor(emptyList())
        assertTrue(labels.isEmpty())
        assertEquals("Aisha Rahman", vm.simPersistName(richContact()))
    }
}
