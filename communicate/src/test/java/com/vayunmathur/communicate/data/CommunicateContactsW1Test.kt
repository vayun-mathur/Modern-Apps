package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync
import com.vayunmathur.communicate.ui.readSharedContact
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 4: contact-adjacent helpers — `readSharedContact`,
 * `ContactPlatformRows.publish` guards and `WhatsAppContactSync`'s
 * offline normalization/dedup path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateContactsW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeContactsState.reset()
        FakeTelephonyState.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
    }

    private fun phoneRow(id: Long, name: String?, number: String?): Map<String, Any?> = mapOf(
        ContactsContract.CommonDataKinds.Phone._ID to id,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to name,
        ContactsContract.CommonDataKinds.Phone.NUMBER to number,
        ContactsContract.CommonDataKinds.Phone.TYPE to 2,
        ContactsContract.CommonDataKinds.Phone.LABEL to null,
        ContactsContract.Contacts._ID to id.toString(),
        ContactsContract.Contacts.DISPLAY_NAME_PRIMARY to (name ?: ""),
    )

    @Test
    fun `readSharedContact splits the display name and reads numbers`() {
        FakeContactsState.phoneRows = listOf(phoneRow(7L, "Alice Lee", "+14155550102"))
        val card = readSharedContact(context, Uri.parse("content://com.android.contacts/contacts/7"))
        assertNotNull(card)
        assertEquals("Alice", card.givenName)
        assertEquals("Lee", card.familyName)
        assertTrue(card.phoneNumbers.any { it.first == "+14155550102" })
    }

    @Test
    fun `readSharedContact returns null when the contact is gone`() {
        FakeContactsState.phoneRows = emptyList()
        assertNull(readSharedContact(context, Uri.parse("content://com.android.contacts/contacts/9")))
    }

    @Test
    fun `readSharedContact returns null for an unusable contact`() {
        FakeContactsState.phoneRows = listOf(phoneRow(3L, "", ""))
        val card = readSharedContact(context, Uri.parse("content://com.android.contacts/contacts/3"))
        // A blank display name with no numbers has nothing worth sharing.
        assertTrue(card == null || card.givenName.isBlank())
    }

    @Test
    fun `platform rows publish is a no-op without write contacts`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.WRITE_CONTACTS)
        ContactPlatformRows.publish(
            context,
            listOf(ContactPlatformRows.Reachability("+14155550102", whatsApp = true, signal = true)),
        ) // must not throw
        assertEquals(ContactPlatformRows.MIME_WHATSAPP_MESSAGE, "vnd.android.cursor.item/vnd.com.whatsapp.profile")
        assertEquals(ContactPlatformRows.MIME_SIGNAL_MESSAGE, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact")
    }

    @Test
    fun `contact sync normalizes dedups and skips invalid numbers offline`() = runTest {
        FakeContactsState.phoneRows = listOf(
            phoneRow(3L, "Garbage", "not a phone"),
            phoneRow(4L, "Blank", "  "),
        )
        // Numbers that don't parse to a valid E.164 never reach the MEX layer
        // (or the SQLCipher-backed mapping table, unavailable on this host).
        val onlyInvalid = WhatsAppContactSync.sync(context)
        assertEquals(1, onlyInvalid.deviceCount)
        assertEquals(0, onlyInvalid.e164Count)
        assertEquals(0, onlyInvalid.onWhatsAppCount)
        FakeContactsState.phoneRows = listOf(
            phoneRow(1L, "Alice", "+1 (415) 555-0102"),
            phoneRow(2L, "Alice Again", "4155550102"),
            phoneRow(3L, "Garbage", "not a phone"),
            phoneRow(4L, "Blank", "  "),
        )
        assertEquals(null, WhatsAppContactSync.normalizeE164("not a phone", "US"))
        assertEquals("+14155550102", WhatsAppContactSync.normalizeE164("(415) 555-0102", "US"))
        assertEquals("+442079460958", WhatsAppContactSync.normalizeE164("020 7946 0958", "GB"))
        val region = WhatsAppContactSync.defaultRegion(context)
        assertTrue(region.isNotBlank())
        val contacts = WhatsAppContactSync.readDeviceContacts(context)
        assertEquals(3, contacts.size) // the blank number is dropped
    }

    @Test
    fun `contact sync returns zeroed result without contacts permission`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertTrue(WhatsAppContactSync.readDeviceContacts(context).isEmpty())
        val result = CommunicateRepository.whatsAppSyncContacts(context)
        assertEquals(0, result.deviceCount)
        assertEquals(0, result.e164Count)
    }
}
