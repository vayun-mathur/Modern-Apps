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

    private fun awaitContacts(vm: ContactViewModel, expectedSize: Int) {
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            vm.contacts.collect {}
        }
        try {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (vm.contacts.value.size == expectedSize) return
                Thread.sleep(10)
            }
            assertEquals(expectedSize, vm.contacts.value.size)
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
        awaitContacts(vm, 3)

        assertEquals(listOf(aisha, cara), withTimeout(15_000) { vm.contactsInGroup(11).first() })
        assertEquals(listOf(bob, cara), withTimeout(15_000) { vm.contactsInGroup(12).first() })
        assertEquals(emptyList(), withTimeout(15_000) { vm.contactsInGroup(99).first() })
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
        // Only group-membership-shaped ops count. ShadowContentResolver keeps
        // providers in a static registry shared by every test in this JVM, so
        // async provider writes left in flight by earlier tests land here too —
        // and Contact.save() itself emits group-membership rows for grouped
        // contacts — so inserts filter on mimetype, deletes on the selection.
        fun groupInserts() = rec.inserts.filter {
            it.second?.getAsString(ContactsContract.Data.MIMETYPE) ==
                ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE
        }
        fun groupDeletes() = rec.deletes.filter {
            it.second?.contains(ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID) == true
        }
        fun groupOpCount(): Int = groupInserts().size + groupDeletes().size
        // The VM re-syncs allContactsState from the provider on every contacts
        // change notification (debounced 300ms) — our own writes, plus stray
        // writes from other tests, all fire it. Pin our rows and confirm they
        // stuck before driving each step.
        fun pinAndSettle(): Boolean {
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

        // A re-sync can still slip between the pin and the fire under load, so
        // verify the recorded evidence matches this test's own ops exactly and
        // retry the step boundedly instead of asserting on global provider state.
        var attempt = 0
        var verified = false
        var lastDetail = ""
        while (attempt < 3 && !verified) {
            attempt++
            rec.batchSizes.clear(); rec.inserts.clear(); rec.deletes.clear()
            if (!pinAndSettle()) { lastDetail = "state would not pin"; continue }

            vm.addContactsToGroup(listOf(1L, 2L), 11L)
            vm.removeContactsFromGroup(listOf(1L), 11L)

            val deadline = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < deadline) {
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (groupOpCount() >= 2) break
                Thread.sleep(20)
            }
            // drain stragglers until the group-op count is stable
            var last = groupOpCount()
            var stableSince = System.nanoTime()
            val drainEnd = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < drainEnd) {
                Thread.sleep(100)
                val cur = groupOpCount()
                if (cur != last) { last = cur; stableSince = System.nanoTime() }
                if (System.nanoTime() - stableSince >= 1_000_000_000L) break
            }

            // one add (SIM contact filtered out) + one remove must have been applied
            val addRawIds =
                groupInserts().map { it.second?.getAsLong(ContactsContract.Data.RAW_CONTACT_ID) }
            val removeCount = groupDeletes().size
            if (addRawIds != listOf(1L) || removeCount != 1) {
                lastDetail = "add/remove evidence: adds=$addRawIds removes=$removeCount"
                continue
            }

            // an all-SIM add is a no-op: nothing more reaches the provider
            if (!pinAndSettle()) { lastDetail = "state would not re-pin"; continue }
            val before = groupOpCount()
            vm.addContactsToGroup(listOf(2L), 11L)
            Thread.sleep(500)
            val after = groupOpCount()
            if (before == after) verified = true
            else lastDetail = "all-SIM add moved provider count $before -> $after"
        }
        assertTrue(verified, "group ops reaching provider after $attempt attempt(s): $lastDetail")
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
