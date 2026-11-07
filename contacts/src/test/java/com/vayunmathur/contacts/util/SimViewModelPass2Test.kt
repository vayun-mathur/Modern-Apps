package com.vayunmathur.contacts.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.SimProviderPass2Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Second-pass coverage for the ViewModel's SIM save path against the fake ICC provider. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimViewModelPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
        // the fake provider records into companion state shared across methods
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

    private fun contact(
        id: Long, name: String, number: String, email: String? = null,
        nickname: String = "", accountName: String? = null,
    ) = Contact(
        id = id, accountType = SIM_ACCOUNT_TYPE, accountName = accountName, isFavorite = false,
        details = ContactDetails(
            phoneNumbers = listOfNotNull(number.takeIf { it.isNotEmpty() }?.let { PhoneNumber(0, it, CDKPhone.TYPE_MOBILE) }),
            emails = listOfNotNull(email?.let { Email(0, it, CDKEmail.TYPE_HOME) }),
            addresses = emptyList(), dates = emptyList(), photos = emptyList(),
            names = listOf(Name(0, "", name.substringBefore(" "), "", name.substringAfter(" ", ""), "")),
            orgs = listOf(Organization(0, "")),
            notes = listOf(Note(0, "")),
            nicknames = listOf(Nickname(0, nickname, CDKNickname.TYPE_DEFAULT)),
            groups = emptyList(),
        ),
    )

    @Test
    fun persistNewSimContactInserts() {
        val vm = vm()
        val fresh = contact(id = 0, name = "New Person", number = "+14155550199", email = "new@x.com", accountName = "5")
        assertTrue(runBlocking { vm.persistSimContact(fresh) })
        assertEquals("content://icc/adn/subId/5", SimProviderPass2Test.FakeIccProvider.lastInsertUri.toString())
        assertEquals("New Person", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("tag"))
    }

    @Test
    fun persistBlankSimContactIsRejected() {
        val vm = vm()
        val blank = contact(id = 0, name = "", number = "")
        assertEquals(false, runBlocking { vm.persistSimContact(blank) })
        assertEquals(null, SimProviderPass2Test.FakeIccProvider.lastInsertUri)
    }

    @Test
    fun persistExistingSimContactUpdatesInPlace() {
        val vm = vm()
        // the provider's Aisha row, projected to a synthetic contact, then renamed
        val listed = SimContactsDataSource.listSimContacts(app)
        val projected = SimContactsDataSource.simContactToContact(listed.first { it.name == "Aisha Rahman" })
        val renamed = projected.copy(
            details = projected.details.copy(
                names = listOf(Name(0, "", "Aisha", "", "Khan", "")),
            )
        )
        assertTrue(runBlocking { vm.persistSimContact(renamed) })
        assertEquals("Aisha Rahman", SimProviderPass2Test.FakeIccProvider.lastUpdateValues?.getAsString("tag"))
        assertEquals("Aisha Khan", SimProviderPass2Test.FakeIccProvider.lastUpdateValues?.getAsString("newTag"))
    }

    @Test
    fun persistUnchangedSimContactShortCircuits() {
        val vm = vm()
        val listed = SimContactsDataSource.listSimContacts(app)
        val projected = SimContactsDataSource.simContactToContact(listed.first { it.name == "Bob" })
        assertTrue(runBlocking { vm.persistSimContact(projected) })
        // unchanged: no update and no insert reached the provider
        assertEquals(null, SimProviderPass2Test.FakeIccProvider.lastUpdateUri)
        assertEquals(null, SimProviderPass2Test.FakeIccProvider.lastInsertUri)
    }

    @Test
    fun findSavedSimContactIdMatchesByNameNumberThenNumberThenName() {
        val vm = vm()
        val simContacts = SimContactsDataSource.simContactsAsContacts(app)
        vm.allContactsState.value = simContacts
        val aisha = simContacts.first { it.name.firstName == "Aisha" }

        // exact name+number
        assertEquals(aisha.id, vm.findSavedSimContactId(aisha))
        // number only (name differs)
        val renamed = aisha.copy(
            details = aisha.details.copy(names = listOf(Name(0, "", "Zed", "", "Zed", "")))
        )
        assertEquals(aisha.id, vm.findSavedSimContactId(renamed))
        // name only (number differs)
        val renumbered = aisha.copy(
            details = aisha.details.copy(
                phoneNumbers = listOf(PhoneNumber(0, "+9999", CDKPhone.TYPE_MOBILE))
            )
        )
        assertEquals(aisha.id, vm.findSavedSimContactId(renumbered))
        // nothing in common
        val stranger = contact(id = -5, name = "Nobody Here", number = "+0000")
        assertNull(vm.findSavedSimContactId(stranger))
    }

    @Test
    fun simPersistNameFallsBackToNicknameThenNumber() {
        val vm = vm()
        assertEquals("Aisha Rahman", vm.simPersistName(contact(0, "Aisha Rahman", "+1")))
        assertEquals("Ash", vm.simPersistName(contact(0, "", "+1", nickname = "Ash")))
        assertEquals("+123", vm.simPersistName(contact(0, "", "+123")))
        assertEquals("", vm.simPersistName(contact(0, "", "")))
    }

    @Test
    fun persistContactDispatchesSimContactsToSimPath() {
        val vm = vm()
        val fresh = contact(id = 0, name = "Via Dispatch", number = "+14155550198", accountName = "5")
        assertTrue(runBlocking { vm.persistContact(fresh) })
        assertEquals("Via Dispatch", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("tag"))
        assertEquals(2_000L, SIM_CONVERGENCE_DELAY_MILLIS)
    }
}
