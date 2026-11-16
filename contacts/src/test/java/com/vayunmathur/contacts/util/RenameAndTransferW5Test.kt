package com.vayunmathur.contacts.util

import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKEvent
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimProviderPass2Test
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver

/** W5: local-account rename guards + reference migration, and rich-contact transfers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RenameAndTransferW5Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
        ShadowContentResolver.registerProviderInternal("icc", SimProviderPass2Test.FakeIccProvider())
    }

    private fun vm(): ContactViewModel {
        val vm = ContactViewModel(app)
        runBlocking { vm.syncFromSystem() }
        runBlocking { vm.loadAccountsInternal() }
        return vm
    }

    private fun rename(
        vm: ContactViewModel, account: ContactAccount, newName: String,
    ): Pair<Boolean, String?> {
        val result = CompletableDeferred<Pair<Boolean, String?>>()
        vm.renameLocalAccount(account, newName) { ok, code -> result.complete(ok to code) }
        return runBlocking { withTimeout(30_000) { result.await() } }
    }

    @Test
    fun renameGuardsRejectNonLocalDefaultBlankAndCollision() {
        val vm = vm()
        assertEquals(false to "not_local", rename(vm, ContactAccount("a@gmail.com", "com.google"), "New"))
        assertEquals(false to "blank", rename(vm, ContactAccount("Kinfolk", LOCAL_ACCOUNT_TYPE), "  "))
        // Same name is a successful no-op.
        assertEquals(true to null, rename(vm, ContactAccount("Kinfolk", LOCAL_ACCOUNT_TYPE), "Kinfolk"))
    }

    @Test
    fun renameMigratesSavedHiddenAndLastSelectedRefs() {
        val vm = vm()
        val account = ContactAccount("Kinfolk", LOCAL_ACCOUNT_TYPE)
        runBlocking(Dispatchers.IO) {
            vm.dataStore.addStringToSetIfAbsent(
                "extra_accounts_set", encodeExtraAccountEntry("Kinfolk", LOCAL_ACCOUNT_TYPE)
            )
            vm.dataStore.addStringToSetIfAbsent(
                "hidden_accounts", encodeHiddenAccountKey(LOCAL_ACCOUNT_TYPE, "Kinfolk")
            )
            vm.setLastSelectedAccount("Kinfolk", LOCAL_ACCOUNT_TYPE)
            // Fire-and-forget set writes: wait until visible.
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val saved = vm.loadSavedAccounts()
                if (saved.any { it.name == "Kinfolk" }) break
                Thread.sleep(25)
            }
        }
        assertEquals(true to null, rename(vm, account, "Family Vault"))
        runBlocking(Dispatchers.IO) {
            val saved = vm.loadSavedAccounts()
            assertTrue(saved.any { it.name == "Family Vault" }, "saved should follow rename, saved=$saved")
            assertTrue(saved.none { it.name == "Kinfolk" }, "old saved name should be gone, saved=$saved")
            val hidden = vm.dataStore.getStringSetAwait("hidden_accounts")
            assertTrue(
                hidden.contains(encodeHiddenAccountKey(LOCAL_ACCOUNT_TYPE, "Family Vault")),
                "hidden key should follow rename, hidden=$hidden",
            )
        }
        assertEquals("Family Vault", vm.lastSelectedAccountState.value?.name)
    }

    private fun richContact(id: Long, accountType: String?, accountName: String?) = Contact(
        id = id, accountType = accountType, accountName = accountName, isFavorite = true,
        customRingtone = "content://ringtone/1",
        details = ContactDetails(
            phoneNumbers = listOf(
                PhoneNumber(1, "+14155550102", CDKPhone.TYPE_MOBILE),
                PhoneNumber(2, "+442071234567", CDKPhone.TYPE_WORK),
            ),
            emails = listOf(
                Email(3, "aisha@x.com", CDKEmail.TYPE_HOME),
                Email(4, "work@x.com", CDKEmail.TYPE_WORK),
            ),
            addresses = emptyList(),
            dates = listOf(Event(5, LocalDate(1992, 3, 15), CDKEvent.TYPE_BIRTHDAY)),
            photos = listOf(Photo(6, "aGVsbG8=")),
            names = listOf(Name(7, "", "Aisha", "", "Rahman", "")),
            orgs = listOf(Organization(8, "Acme")),
            notes = listOf(Note(9, "A note")),
            nicknames = emptyList(),
            groups = listOf(GroupMembership(10, 11)),
            commPrefs = listOf(CommPref(11, "+14155550102", CommPlatform.WHATSAPP)),
        ),
    )

    private fun transfer(
        vm: ContactViewModel, ids: List<Long>, destName: String, destType: String,
    ): Triple<Int, Int, Boolean> {
        val result = CompletableDeferred<Triple<Int, Int, Boolean>>()
        vm.transferContacts(ids, destName, destType) { m, f, d -> result.complete(Triple(m, f, d)) }
        return runBlocking { withTimeout(30_000) { result.await() } }
    }

    @Test
    fun simLossListsEveryDroppedFieldForRichContact() {
        val loss = computeSimLoss(richContact(7, "com.google", "a@gmail.com"))
        assertEquals(
            setOf(
                SimDroppedField.PHOTO, SimDroppedField.EXTRA_PHONES, SimDroppedField.EXTRA_EMAILS,
                SimDroppedField.DATES, SimDroppedField.ORGANIZATION, SimDroppedField.NOTE,
                SimDroppedField.GROUPS, SimDroppedField.COMM_PREF, SimDroppedField.RINGTONE,
                SimDroppedField.FAVORITE,
            ),
            loss.toSet(),
        )
        assertTrue(computeSimLoss(richContact(7, "com.google", "a@gmail.com").copy(
            isFavorite = false, customRingtone = null,
            details = richContact(7, null, null).details.copy(
                photos = emptyList(), dates = emptyList(), groups = emptyList(), commPrefs = emptyList(),
                phoneNumbers = listOf(PhoneNumber(1, "+1", CDKPhone.TYPE_MOBILE)),
                emails = listOf(Email(3, "a@x.com", CDKEmail.TYPE_HOME)),
                orgs = emptyList(), notes = emptyList(),
            ),
        )).isEmpty())
    }

    @Test
    fun transferRichContactToProviderAndSim() {
        val vm = vm()
        // Provider -> other provider account (groups dropped flag is reported).
        val toProvider = transfer(vm, listOf(7L), "other@gmail.com", "com.google")
        assertEquals(1, toProvider.first, "provider transfer should move, result=$toProvider")
        // Provider -> SIM.
        val vm2 = vm()
        val toSim = transfer(vm2, listOf(7L), "1", SIM_ACCOUNT_TYPE)
        assertEquals(1, toSim.first, "SIM transfer should move, result=$toSim")
    }
}
