package com.vayunmathur.contacts.util

import android.app.Application
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.R
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.DefaultAccountKind
import com.vayunmathur.contacts.data.DefaultAccountSnapshot
import com.vayunmathur.contacts.data.DefaultAccountSource
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.ProviderAccount
import com.vayunmathur.contacts.data.SaveOutcome
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #916: with a cloud default account for new contacts (Android 16+), new drafts default to
 * that account instead of On-Device, and a provider refusal of the device account surfaces
 * as a clear error instead of "Save failed". A non-cloud default changes nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CloudDefaultAccountPass2Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val google = ProviderAccount("me@gmail.com", "com.google")

    /** Contacts provider that refuses every write the way AccountResolver does under a cloud default. */
    class RejectingProvider : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor? = null

        override fun applyBatch(
            authority: String, operations: ArrayList<ContentProviderOperation>,
        ): Array<ContentProviderResult> = throw rejection()

        override fun insert(uri: Uri, values: ContentValues?): Uri = throw rejection()
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
        ): Int = 0

        override fun getType(uri: Uri): String? = null

        private fun rejection() = IllegalArgumentException(
            "Cannot add contacts to local or SIM accounts when default account is set to cloud"
        )
    }

    private class FakeSource(private val snapshot: DefaultAccountSnapshot?) : DefaultAccountSource {
        override fun defaultAccountForNewContacts() = snapshot
        override fun customLocalAccount(): ProviderAccount? = null
        override fun providerSimAccounts(): List<ProviderAccount> = emptyList()
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, RejectingProvider())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(snapshot: DefaultAccountSnapshot?): ContactViewModel {
        val vm = ContactViewModel(app, FakeSource(snapshot))
        runBlocking { vm.syncFromSystem() }
        return vm
    }

    private fun newDeviceContact() = Contact(
        id = 0L, accountType = null, accountName = null, isFavorite = false,
        details = ContactDetails.empty().copy(
            names = listOf(Name(0, "", "New", "", "Person", "")),
            phoneNumbers = listOf(PhoneNumber(0, "+14155550199", CDKPhone.TYPE_MOBILE)),
        ),
    )

    @Test
    fun saveMapsProviderRefusalToCloudDefaultOutcome() {
        val contact = newDeviceContact()
        assertEquals(
            SaveOutcome.REJECTED_CLOUD_DEFAULT,
            contact.saveWithOutcome(app, contact.details, ContactDetails.empty()),
        )
        assertFalse(contact.save(app, contact.details, ContactDetails.empty()))
    }

    @Test
    fun cloudDefaultSeedsNewDraftWithCloudAccount() {
        val vm = vm(DefaultAccountSnapshot(DefaultAccountKind.CLOUD, google))
        val restriction = assertNotNull(vm.newContactRestriction.value)
        assertTrue(restriction.blocks("", ""))
        // Offered in the pickers even when it holds no contacts yet.
        assertTrue(ContactAccount(google.name, google.type) in vm.accounts.value)

        vm.initEditDraft(null)
        val draft = assertNotNull(vm.editDraft.value)
        assertEquals(google.name, draft.accountName)
        assertEquals(google.type, draft.accountType)
    }

    @Test
    fun refusedEditorSaveExplainsWhy() {
        val vm = vm(DefaultAccountSnapshot(DefaultAccountKind.CLOUD, google))
        vm.initEditDraft(null)
        // The user forces On-Device anyway (e.g. a draft seeded before the default changed).
        vm.updateEditDraft {
            it.copy(
                accountName = "", accountType = "", firstName = "New",
                phoneNumbers = listOf(PhoneNumber(0, "+14155550199", CDKPhone.TYPE_MOBILE)),
            )
        }
        val result = CompletableDeferred<Pair<Boolean, String?>>()
        vm.saveEditDraft { ok, err, _ -> result.complete(ok to err) }
        val (ok, err) = runBlocking { withTimeout(30_000) { result.await() } }
        assertFalse(ok)
        assertEquals(app.getString(R.string.cloud_default_rejected, google.name), err)
    }

    @Test
    fun nonCloudDefaultKeepsOnDeviceDraft() {
        val vm = vm(DefaultAccountSnapshot(DefaultAccountKind.LOCAL, null))
        assertNull(vm.newContactRestriction.value)
        vm.initEditDraft(null)
        val draft = assertNotNull(vm.editDraft.value)
        assertEquals("", draft.accountName)
        assertEquals("", draft.accountType)
    }
}
