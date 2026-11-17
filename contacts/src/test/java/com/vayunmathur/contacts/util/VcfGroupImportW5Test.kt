package com.vayunmathur.contacts.util
import com.vayunmathur.contacts.platform.ContactViewModel
import com.vayunmathur.contacts.platform.importVcfContacts
import com.vayunmathur.contacts.platform.parseVcfUris

import android.app.Application
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimProviderPass2Test
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** W5: VCF CATEGORIES group resolution and SIM/provider bulk import branches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class VcfGroupImportW5Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
    }

    private fun fileWith(text: String): Uri {
        val f = File(app.cacheDir, "w5-${System.nanoTime()}.vcf")
        f.writeText(text)
        return Uri.fromFile(f)
    }

    private fun parsed(vm: ContactViewModel): List<Contact> = runBlocking {
        withTimeout(30_000) { vm.parsedVcfContacts.first { it != null } }!!
    }

    @Test
    fun categoriesResolveExistingAndCreateNewGroups() {
        val vm = ContactViewModel(app)
        vm.parseVcfUris(
            listOf(
                fileWith(
                    """
                    BEGIN:VCARD
                    VERSION:3.0
                    FN:Aisha Rahman
                    N:Rahman;Aisha;;;
                    TEL;TYPE=CELL:+1 415 555 0102
                    CATEGORIES:Family,NewClubW5, family ,
                    END:VCARD
                    """.trimIndent()
                )
            )
        )
        val contacts = parsed(vm)
        assertEquals(1, contacts.size)
        val groupIds = contacts[0].details.groups.map { it.groupId }
        // Family exists in the fake provider as id 11; the new club is created via insert.
        assertTrue(groupIds.contains(11L), "existing Family group should resolve to 11, got $groupIds")
        assertTrue(groupIds.size >= 2, "new group should also be resolved, got $groupIds")
        assertEquals(2, groupIds.distinct().size, "Family (twice, differing case) plus the new club should resolve to two groups, got $groupIds")
    }

    @Test
    fun importToSimUsesPhoneAsNameWhenNameBlankAndSkipsEmptyContacts() = runBlocking {
        val vm = ContactViewModel(app)
        val withPhoneOnly = Contact(
            id = 0, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails.empty().copy(
                phoneNumbers = listOf(PhoneNumber(0, " +14155550199 ", CDKPhone.TYPE_MOBILE)),
                emails = listOf(Email(0, " only@x.com ", CDKEmail.TYPE_HOME)),
                names = listOf(Name(0, "", "", "", "", "")),
            ),
        )
        val empty = Contact(id = 0, accountType = null, accountName = null, isFavorite = false, details = ContactDetails.empty())
        val done = CompletableDeferred<String?>()
        vm.importVcfContacts(listOf(withPhoneOnly, empty), "1", SIM_ACCOUNT_TYPE) { done.complete(it) }
        assertEquals(null, withTimeout(30_000) { done.await() })
        assertEquals("+14155550199", SimProviderPass2Test.FakeIccProvider.lastInsertValues?.getAsString("number")?.trim())
    }

    @Test
    fun importToProviderAccountCompletesWithoutRejection() = runBlocking {
        val vm = ContactViewModel(app)
        val contact = Contact(
            id = 0, accountType = null, accountName = null, isFavorite = false,
            details = ContactDetails.empty().copy(
                phoneNumbers = listOf(PhoneNumber(0, "+14155550102", CDKPhone.TYPE_MOBILE)),
                emails = listOf(Email(0, "a@x.com", CDKEmail.TYPE_HOME)),
                names = listOf(Name(0, "", "Aisha", "", "Rahman", "")),
            ),
        )
        val done = CompletableDeferred<String?>()
        vm.importVcfContacts(listOf(contact), "On device", "com.vayunmathur.contacts.local") { done.complete(it) }
        assertEquals(null, withTimeout(30_000) { done.await() })
    }

    @Test
    fun parseMultipleUrisAccumulatesAndBadUriIsSkipped() {
        val vm = ContactViewModel(app)
        val good = fileWith("BEGIN:VCARD\nVERSION:3.0\nFN:Solo Person\nN:Person;Solo;;;\nEND:VCARD")
        vm.parseVcfUris(listOf(Uri.fromFile(File(app.cacheDir, "nope-w5.vcf")), good, good))
        val contacts = parsed(vm)
        assertEquals(2, contacts.size)
        assertTrue(contacts.all { it.name.firstName == "Solo" })
    }
}
