package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.AssistantIntentsPass2Test
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimProviderPass2Test
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** Final-gap VM coverage: group membership ops/queries and VCF-to-SIM import. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GroupAndSimImportPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal(
            "icc", SimProviderPass2Test.FakeIccProvider()
        )
    }

    private fun contact(
        id: Long, first: String, accountType: String? = null, groupIds: List<Long> = emptyList(),
    ) = Contact(
        id = id, accountType = accountType, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, "+1415555010$id", CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(id, "${first.lowercase()}@x.com", CDKEmail.TYPE_HOME)),
            addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(id, "", first, "", "Lee", "")),
            orgs = listOf(Organization(id, "Acme")),
            notes = listOf(Note(id, "")),
            nicknames = listOf(Nickname(id, "", CDKNickname.TYPE_DEFAULT)),
            groups = groupIds.mapIndexed { i, gid -> GroupMembership(id * 100 + i, gid) },
        ),
    )

    private fun awaitContacts(vm: ContactViewModel, expected: List<Contact>) {
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            vm.contacts.collect {}
        }
        try {
            // The VM's init/debounced sync overwrites allContactsState with
            // the provider+SIM list whenever it lands, so a single seeding
            // races it: keep re-seeding until the derived list is exactly
            // the fixture set. Step counted (Robolectric clock runs ahead).
            var steps = 0
            while (steps++ < 800) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (vm.contacts.value == expected) return
                vm.allContactsState.value = expected
                try { Thread.sleep(25) } catch (_: InterruptedException) { }
            }
            assertEquals(expected, vm.contacts.value)
        } finally {
            job.cancel()
        }
    }

    private fun seededVm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    @Test
    fun contactsInGroupFiltersByMembership() = runBlocking {
        val vm = seededVm()
        val aisha = contact(1, "Aisha", groupIds = listOf(11))
        val bob = contact(2, "Bob", groupIds = listOf(12))
        val cara = contact(3, "Cara", groupIds = listOf(11, 12))
        vm.allContactsState.value = listOf(aisha, bob, cara)
        awaitContacts(vm, listOf(aisha, bob, cara))

        // first { it == expected }, not first(): the WhileSubscribed
        // membership flow can still hold its empty initial value when the
        // collector starts under load.
        assertEquals(listOf(aisha, cara), withTimeout(15_000) { vm.contactsInGroup(11).first { it == listOf(aisha, cara) } })
        assertEquals(listOf(bob, cara), withTimeout(15_000) { vm.contactsInGroup(12).first { it == listOf(bob, cara) } })
        assertEquals(emptyList(), withTimeout(15_000) { vm.contactsInGroup(99).first { it.isEmpty() && vm.contacts.value.size == 3 } })
        assertEquals(aisha, vm.findContact(1))
        assertEquals(null, vm.findContact(999))
    }

    @Test
    fun addAndRemoveGroupOpsReachProviderSkippingSimContacts() {
        val vm = seededVm()
        val device = contact(1, "Aisha")
        val sim = contact(2, "Simmy", accountType = SIM_ACCOUNT_TYPE)

        // recording provider takes over so the group batches are observable
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, AssistantIntentsPass2Test.RecordingProvider()
        )
        val rec = AssistantIntentsPass2Test.RecordingProvider
        var attempt = 0
        var verified = false
        var lastDetail = ""
        while (attempt < 3 && !verified) {
            attempt++
            val detail = runGroupOpsAttempt(vm, device, sim, rec)
            if (detail == null) verified = true else lastDetail = detail
        }
        assertTrue(verified, "group ops reaching provider after $attempt attempt(s): $lastDetail")
    }

    // Only group-membership-shaped ops count. ShadowContentResolver keeps
    // providers in a static registry shared by every test in this JVM, so
    // async provider writes left in flight by earlier tests land here too —
    // and Contact.save() itself emits group-membership rows for grouped
    // contacts — so inserts filter on mimetype, deletes on the selection.
    private fun groupInserts(
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ) = rec.inserts.filter {
        it.second?.getAsString(ContactsContract.Data.MIMETYPE) ==
            ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
    }

    private fun groupDeletes(
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ) = rec.deletes.filter {
        it.second?.contains(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID) == true
    }

    private fun groupOpCount(
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ): Int = groupInserts(rec).size + groupDeletes(rec).size

    // The VM re-syncs allContactsState from the provider on every contacts
    // change notification (debounced 300ms) — our own writes, plus stray
    // writes from other tests, all fire it. Pin our rows and confirm they
    // stuck before driving each step.
    private fun pinAndSettle(
        vm: ContactViewModel, device: Contact, sim: Contact,
    ): Boolean {
        val deadline = System.nanoTime() + 10_000_000_000L
        var stableSince = 0L
        while (System.nanoTime() < deadline) {
            if (vm.allContactsState.value.map { it.id } != listOf(1L, 2L)) {
                vm.allContactsState.value = listOf(device, sim)
                stableSince = 0L
            } else if (stableSince == 0L) {
                stableSince = System.nanoTime()
            } else if (System.nanoTime() - stableSince >= 500_000_000L) {
                return true
            }
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        return vm.allContactsState.value.map { it.id } == listOf(1L, 2L)
    }

    private fun waitForGroupOps(
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (groupOpCount(rec) >= 2) break
            Thread.sleep(20)
        }
    }

    private fun drainGroupOps(
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ) {
        // drain stragglers until the group-op count is stable
        var last = groupOpCount(rec)
        var stableSince = System.nanoTime()
        val drainEnd = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < drainEnd) {
            Thread.sleep(100)
            val cur = groupOpCount(rec)
            if (cur != last) { last = cur; stableSince = System.nanoTime() }
            if (System.nanoTime() - stableSince >= 1_000_000_000L) break
        }
    }

    /**
     * One bounded add/remove attempt. Returns null when the recorded provider
     * evidence matches this test's own ops exactly, otherwise a failure detail.
     * A re-sync can slip between the pin and the fire under load, so the caller
     * retries boundedly instead of asserting on global provider state.
     */
    private fun runGroupOpsAttempt(
        vm: ContactViewModel,
        device: Contact,
        sim: Contact,
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ): String? {
        rec.batchSizes.clear(); rec.inserts.clear(); rec.deletes.clear()
        if (!pinAndSettle(vm, device, sim)) return "state would not pin"
        vm.addContactsToGroup(listOf(1L, 2L), 11L)
        vm.removeContactsFromGroup(listOf(1L), 11L)
        waitForGroupOps(rec)
        drainGroupOps(rec)
        // one add (SIM contact filtered out) + one remove must have been applied
        val addRawIds =
            groupInserts(rec).map { it.second?.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) }
        val removeCount = groupDeletes(rec).size
        if (addRawIds != listOf(1L) || removeCount != 1) {
            return "add/remove evidence: adds=$addRawIds removes=$removeCount"
        }
        return verifyAllSimAddIsNoOp(vm, device, sim, rec)
    }

    /** An all-SIM add is a no-op: nothing more reaches the provider. */
    private fun verifyAllSimAddIsNoOp(
        vm: ContactViewModel,
        device: Contact,
        sim: Contact,
        rec: AssistantIntentsPass2Test.RecordingProvider.Companion,
    ): String? {
        if (!pinAndSettle(vm, device, sim)) return "state would not re-pin"
        val before = groupOpCount(rec)
        vm.addContactsToGroup(listOf(2L), 11L)
        Thread.sleep(500)
        val after = groupOpCount(rec)
        return if (before == after) null else "all-SIM add moved provider count $before -> $after"
    }

    @Test
    fun importVcfContactsToSimWritesAdnRows() {
        val vm = seededVm()
        SimProviderPass2Test.FakeIccProvider.lastInsertUri = null
        SimProviderPass2Test.FakeIccProvider.lastInsertValues = null

        val imported = contact(0, "Imported").copy(
            details = contact(0, "Imported").details.copy(
                names = listOf(Name(0, "", "Imported", "", "Sim", "")),
            )
        )
        val done = CompletableDeferred<Unit>()
        vm.importVcfContacts(listOf(imported), "5", SIM_ACCOUNT_TYPE) { done.complete(Unit) }
        runBlocking { withTimeout(30_000) { done.await() } }

        assertEquals(
            "content://icc/adn/subId/5",
            SimProviderPass2Test.FakeIccProvider.lastInsertUri.toString(),
        )
        assertEquals(
            "Imported Sim",
            SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("tag"),
        )
        assertEquals(
            "+14155550100",
            SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("number"),
        )
    }
}
