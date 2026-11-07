package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.SimProviderPass2Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Second-pass coverage for add-phone, draft saving of existing contacts, and SIM transfers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DraftTransferPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        SimProviderPass2Test.FakeIccProvider.lastInsertUri = null
        SimProviderPass2Test.FakeIccProvider.lastInsertValues = null
        SimProviderPass2Test.FakeIccProvider.lastUpdateUri = null
        SimProviderPass2Test.FakeIccProvider.lastUpdateValues = null
    }


    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun awaitDone(block: ((Boolean, String?, Long?) -> Unit) -> Unit): Triple<Boolean, String?, Long?> {
        val signal = CompletableDeferred<Triple<Boolean, String?, Long?>>()
        block { ok, err, id -> signal.complete(Triple(ok, err, id)) }
        return runBlocking { withTimeout(30_000) { signal.await() } }
    }

    @Test
    fun addPhoneNumberToExistingProviderContact() {
        val vm = vm()
        val done = CompletableDeferred<Unit>()
        vm.addPhoneNumberToContact(7L, "+14155550199", CDKPhone.TYPE_WORK) { done.complete(Unit) }
        runBlocking { withTimeout(30_000) { done.await() } }
        // completing without error against the fake provider is the behavior;
        // a duplicate add (same digits, different formatting) also completes
        val done2 = CompletableDeferred<Unit>()
        vm.addPhoneNumberToContact(7L, "+1 415 555 0199", CDKPhone.TYPE_WORK) { done2.complete(Unit) }
        runBlocking { withTimeout(30_000) { done2.await() } }
        // unknown contact still completes (the dialer flow must always call back)
        val done3 = CompletableDeferred<Unit>()
        vm.addPhoneNumberToContact(999L, "+14155550199") { done3.complete(Unit) }
        runBlocking { withTimeout(30_000) { done3.await() } }
    }

    @Test
    fun addPhoneNumberToSimContactInsertsAdnRow() {
        val vm = vm()
        val simContacts = SimContactsDataSource.simContactsAsContacts(app)
        vm.allContactsState.value = simContacts
        val bob = simContacts.first { it.name.firstName == "Bob" } // Bob has no number yet
        val done = CompletableDeferred<Unit>()
        vm.addPhoneNumberToContact(bob.id, "+14155550177") { done.complete(Unit) }
        runBlocking { withTimeout(30_000) { done.await() } }
        assertEquals("+14155550177", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("number"))
    }

    @Test
    fun saveEditDraftForExistingContactUpdates() {
        val vm = vm()
        vm.initEditDraft(7L)
        assertEquals("Aisha", vm.editDraft.value?.firstName)
        vm.updateEditDraft { it.copy(noteContent = "Updated note", company = "NewCo") }
        val (ok, err, savedId) = awaitDone { vm.saveEditDraft(it) }
        assertTrue(ok, "existing-contact save should succeed against the fake provider, err=$err")
        assertEquals(7L, savedId)
    }

    @Test
    fun saveEditDraftWithBirthdayAndGroups() {
        val vm = vm()
        vm.initEditDraft(7L)
        vm.updateEditDraft {
            it.copy(
                birthday = kotlinx.datetime.LocalDate(1990, 1, 2),
                groupMemberships = listOf(com.vayunmathur.contacts.data.GroupMembership(0, 11L)),
            )
        }
        val (ok, err, _) = awaitDone { vm.saveEditDraft(it) }
        assertTrue(ok, "save with birthday/groups should succeed, err=$err")
    }

    @Test
    fun transferProviderContactToSimDropsGroups() {
        val vm = vm()
        val result = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(listOf(7L), "5", SIM_ACCOUNT_TYPE) { m, f, d -> result.complete(Triple(m, f, d)) }
        val (moved, failed, groupsDropped) = runBlocking { withTimeout(30_000) { result.await() } }
        assertEquals(1, moved)
        assertEquals(0, failed)
        assertTrue(groupsDropped)
        // the SIM insert carried Aisha's first number
        assertEquals("+14155550102", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("number"))
        assertEquals("content://icc/adn/subId/5", SimProviderPass2Test.FakeIccProvider.lastInsertUri.toString())
    }

    @Test
    fun transferSimContactToProvider() {
        val vm = vm()
        val simContacts = SimContactsDataSource.simContactsAsContacts(app)
        vm.allContactsState.value = simContacts
        val aisha = simContacts.first { it.name.firstName == "Aisha" }
        val result = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(listOf(aisha.id), "other@gmail.com", "com.google") { m, f, d ->
            result.complete(Triple(m, f, d))
        }
        val (moved, failed, _) = runBlocking { withTimeout(30_000) { result.await() } }
        assertEquals(1, moved)
        assertEquals(0, failed)
    }

    @Test
    fun transferSimToSim() {
        val vm = vm()
        val simContacts = SimContactsDataSource.simContactsAsContacts(app)
        vm.allContactsState.value = simContacts
        val bob = simContacts.first { it.name.firstName == "Bob" }
        val result = CompletableDeferred<Triple<Int, Int, Boolean>>()
        // Bob's accountName is "" (no subId); moving to SIM slot 5 is a SIM->SIM move
        vm.transferContacts(listOf(bob.id), "5", SIM_ACCOUNT_TYPE) { m, f, d ->
            result.complete(Triple(m, f, d))
        }
        val (moved, failed, _) = runBlocking { withTimeout(30_000) { result.await() } }
        assertEquals(1, moved)
        assertEquals(0, failed)
    }
}
