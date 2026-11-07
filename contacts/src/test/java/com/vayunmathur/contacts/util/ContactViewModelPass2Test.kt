package com.vayunmathur.contacts.util

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.ContactGroup
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.PrefillValue
import com.vayunmathur.contacts.data.CDKNickname
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Second-pass ViewModel coverage under Robolectric (empty system provider). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ContactViewModelPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }

    private fun contact(id: Long, first: String, phone: String = "+1415555010$id") = Contact(
        id = id, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOf(PhoneNumber(id, phone, CDKPhone.TYPE_MOBILE)),
            emails = listOf(Email(id, "${first.lowercase()}@x.com", CDKEmail.TYPE_HOME)),
            addresses = listOf(Address(id, "1 Main St", CDKStructuredPostal.TYPE_HOME)),
            dates = emptyList(), photos = emptyList(),
            names = listOf(Name(id, "", first, "", "Lee", "")),
            orgs = listOf(Organization(id, "Acme")),
            notes = listOf(Note(id, "note-$first")),
            nicknames = listOf(Nickname(id, "", CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    /**
     * Waits until [ContactViewModel.contacts] equals [expected], idling Robolectric's
     * main looper so the WhileSubscribed stateIn collectors can run.
     */
    private fun awaitContacts(vm: ContactViewModel, expected: List<Contact>) {
        // subscribing is what starts the WhileSubscribed upstreams
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            vm.contacts.collect {}
        }
        try {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                if (vm.contacts.value == expected) return
                Thread.sleep(10)
            }
            assertEquals(expected, vm.contacts.value)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun searchFiltersContactsByNamePhoneAndTokens() {
        val vm = ContactViewModel(app)
        // let the init sync settle first, or it would overwrite the fixture list
        runBlocking { vm.syncFromSystem() }
        val aisha = contact(1, "Aisha")
        val bob = contact(2, "Bob")
        vm.allContactsState.value = listOf(aisha, bob)

        awaitContacts(vm, listOf(aisha, bob))
        vm.setSearchQuery("aisha")
        assertEquals("aisha", vm.searchQuery.value)
        awaitContacts(vm, listOf(aisha))
        // multi-token: name token + phone fragment must both match
        vm.setSearchQuery("bob 555")
        awaitContacts(vm, listOf(bob))
        // searching by email domain fragment works too
        vm.setSearchQuery("aisha@x")
        awaitContacts(vm, listOf(aisha))
        vm.setSearchQuery("no-such-person")
        awaitContacts(vm, emptyList())
        assertEquals(aisha, vm.getContact(1))
        assertEquals(bob, vm.getContact(2))
        assertNull(vm.getContact(999))
    }

    @Test
    fun syncFromSystemMarksLoadedWithEmptyProvider() {
        val vm = ContactViewModel(app)
        assertEquals(false, vm.hasLoadedContacts.value)
        runBlocking { vm.syncFromSystem() }
        assertEquals(true, vm.hasLoadedContacts.value)
        // loadContacts only emits a sync trigger; calling it must not throw
        vm.loadContacts()
    }

    @Test
    fun editDraftLifecycleForNewAndExistingContacts() {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        val aisha = contact(7, "Aisha")
        vm.allContactsState.value = listOf(aisha)

        vm.initEditDraft(null)
        val blank = assertNotNull(vm.editDraft.value)
        assertEquals("", blank.firstName)
        // re-init with the same id is a no-op once initialized
        vm.updateEditDraft { it.copy(firstName = "Typed") }
        vm.initEditDraft(null)
        assertEquals("Typed", vm.editDraft.value!!.firstName)

        vm.clearEditDraft()
        assertNull(vm.editDraft.value)

        vm.initEditDraft(7L)
        val loaded = assertNotNull(vm.editDraft.value)
        assertEquals("Aisha", loaded.firstName)
        assertEquals("Lee", loaded.lastName)
        assertEquals(listOf("+14155550107"), loaded.phoneNumbers.map { it.number })
        assertEquals(listOf("aisha@x.com"), loaded.emails.map { it.address })

        vm.updateEditDraft { it.copy(noteContent = "updated") }
        assertEquals("updated", vm.editDraft.value!!.noteContent)

        // unknown id falls back to a blank draft
        vm.clearEditDraft()
        vm.initEditDraft(4242L)
        assertEquals("", assertNotNull(vm.editDraft.value).firstName)
    }

    @Test
    fun draftPhotoFromBitmapEncodesIntoDraft() {
        val vm = ContactViewModel(app)
        vm.initEditDraft(null)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        vm.setEditDraftPhotoFromBitmap(bitmap)
        runBlocking {
            withTimeout(10_000) { vm.editDraft.first { it?.photo != null } }
        }
        assertTrue(vm.editDraft.value!!.photo!!.value.isNotEmpty())
    }

    @Test
    fun decodePhotoHandlesValidAndInvalidBase64() {
        val vm = ContactViewModel(app)
        // 1x1 PNG in Base64
        val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII="
        assertNotNull(vm.decodePhoto(png))
        // second call hits the cache path
        assertNotNull(vm.decodePhoto(png))
        assertNull(vm.decodePhoto("!!!not-base64!!!"))
    }

    @Test
    fun accountEncodingRoundTripsAndSelection() {
        assertEquals("a%25b", encodeAccountComponent("a%b"))
        assertEquals("a|b", decodeAccountComponent("a%7Cb"))
        assertEquals("a,b", decodeAccountComponent("a%2Cb"))
        assertEquals("plain", encodeAccountComponent("plain"))
        val entry = encodeExtraAccountEntry("My,Account|%", "type|x")
        val decoded = decodeExtraAccountEntryClean(entry)
        assertNotNull(decoded)
        assertEquals("My,Account|%", decoded.name)
        assertEquals("type|x", decoded.type)
        assertNull(decodeExtraAccountEntryClean("no-separator"))
        val hidden = encodeHiddenAccountKey("type|x", "na,me")
        assertEquals("type|x" to "na,me", decodeHiddenKeyClean(hidden))
        assertNull(decodeHiddenKeyClean("badentry"))

        val vm = ContactViewModel(app)
        val (selection, selArgs) = vm.accountSelection("com.google", "a@gmail.com")
        assertTrue(selection.contains("account_type = ?"), selection)
        assertTrue(selection.contains("account_name = ?"), selection)
        assertEquals(listOf("com.google", "a@gmail.com"), selArgs.toList())
        val (emptySelection, emptyArgs) = vm.accountSelection("", "")
        assertTrue(emptySelection.contains("IS NULL"), emptySelection)
        assertEquals(0, emptyArgs.size)
        assertEquals("+14155550102", vm.normalizePhoneForCompare("+1 (415) 555-0102"))
        assertEquals("", vm.normalizePhoneForCompare("abc"))
    }

    @Test
    fun mergeHelpersDeduplicateByValue() {
        val vm = ContactViewModel(app)
        val existing = listOf(PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE))
        val merged = vm.mergePhones(
            existing,
            listOf(
                PrefillValue("+1 415 555 0102", CDKPhone.TYPE_HOME), // same digits: skipped
                PrefillValue("+442071234567", CDKPhone.TYPE_WORK),
                PrefillValue("   "), // blank: skipped
            ),
        )
        assertEquals(listOf("+14155550102", "+442071234567"), merged.map { it.number })
        assertEquals(existing, vm.mergePhones(existing, emptyList()))

        val emails = vm.mergeEmails(
            listOf(Email(1, "A@x.com", CDKEmail.TYPE_HOME)),
            listOf(PrefillValue("a@x.com"), PrefillValue("b@x.com", CDKEmail.TYPE_WORK), PrefillValue(" ")),
        )
        assertEquals(listOf("A@x.com", "b@x.com"), emails.map { it.address })

        val addresses = vm.mergeAddresses(
            listOf(Address(1, "1 Main St", CDKStructuredPostal.TYPE_HOME)),
            listOf(PrefillValue("1 main st"), PrefillValue("2 Oak St")),
        )
        assertEquals(listOf("1 Main St", "2 Oak St"), addresses.map { it.formattedAddress })
    }

    @Test
    fun groupsStateAndSortMode() {
        val vm = ContactViewModel(app)
        runBlocking {
            withTimeout(10_000) { vm.groups.first { it.isEmpty() } }
        }
        // Every setter below launches a DataStore write on the VM scope. Await each
        // write's visible effect before returning: leaving one in flight at Robolectric
        // teardown can wedge the shared DataStore write actor for later test classes
        // in the same JVM (observed as hung executors in MigrationPass2Test).
        fun awaitStore(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!condition() && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue(condition(), description)
        }

        vm.setSortMode(ContactSorting.ContactSortMode.LastName)
        awaitStore("sort mode write should land") {
            vm.dataStore.getString("sort_mode") == ContactSorting.ContactSortMode.LastName.name
        }

        vm.loadAccounts()
        vm.setLastSelectedAccount("a@gmail.com", "com.google")
        awaitStore("last selected account should be stored") {
            vm.lastSelectedAccountState.value == ContactAccount("a@gmail.com", "com.google") &&
                vm.dataStore.getString("last_account_name") == "a@gmail.com"
        }
        vm.loadLastSelectedAccount()
        assertEquals("a@gmail.com", vm.lastSelectedAccountState.value?.name)

        vm.setShowAccountLabels(false)
        awaitStore("account labels off should land") {
            !vm.dataStore.getBoolean("show_account_labels", true)
        }
        vm.setShowAccountLabels(true)
        awaitStore("account labels on should land") {
            vm.dataStore.getBoolean("show_account_labels", false)
        }

        // groups flow exists and ContactGroup equality works
        assertEquals(ContactGroup(1, "Family"), ContactGroup(1, "Family").copy())
    }
}
