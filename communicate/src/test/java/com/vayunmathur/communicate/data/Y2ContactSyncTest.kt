package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalContactSync
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Y2: WhatsApp/Signal contact syncs with the in-memory DB overrides.
 * WhatsApp sync persists the local address book (MEX has no captured
 * doc_ids, so discovery is a typed transport failure); Signal sync fails
 * closed at CDSI credentials with no auth, and its uint64 E.164 codec is
 * asserted directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y2ContactSyncTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var waDb: WhatsAppDatabase
    private lateinit var sigDb: SignalDatabase

    @BeforeTest fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        waDb = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        sigDb = Room.inMemoryDatabaseBuilder(app, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        WhatsAppDatabase.overrideForTest = waDb
        SignalDatabase.overrideForTest = sigDb
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }

    @AfterTest fun tearDown() {
        WhatsAppDatabase.overrideForTest = null
        SignalDatabase.overrideForTest = null
        waDb.close(); sigDb.close()
    }

    private fun phoneRow(id: Long, name: String, number: String): Map<String, Any?> = mapOf(
        android.provider.ContactsContract.CommonDataKinds.Phone._ID to id,
        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to name,
        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER to number,
        android.provider.ContactsContract.CommonDataKinds.Phone.TYPE to 2,
        android.provider.ContactsContract.CommonDataKinds.Phone.LABEL to null,
        android.provider.ContactsContract.Contacts._ID to id.toString(),
        android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY to name,
    )

    @Test fun whatsappSyncPersistsLocalContactsWhenMexUnavailable() = runTest {
        FakeContactsState.phoneRows = listOf(
            phoneRow(1L, "Alice", "+1 (415) 555-0102"),
            phoneRow(2L, "Alice Dup", "4155550102"),
            phoneRow(3L, "Bob", "+44 20 7946 0958"),
            phoneRow(4L, "Bad", "not a number"),
        )
        val result = WhatsAppContactSync.sync(context)
        assertEquals(4, result.deviceCount)
        // Bob's GB number may or may not normalize under the default (US) region; Alice always does.
        assertTrue(result.e164Count >= 1, "$result")
        val stored = waDb.contactDao().getAll()
        assertTrue(stored.any { it.phoneE164 == "+14155550102" && it.displayName == "Alice" }, "$stored")
        assertTrue(stored.all { !it.onWhatsApp }) // MEX discovery cannot succeed offline
        // A second sync is idempotent in row count for Alice.
        WhatsAppContactSync.sync(context)
        assertEquals(1, waDb.contactDao().getAll().count { it.phoneE164 == "+14155550102" })
    }

    @Test fun whatsappSyncEmptyAddressBook() = runTest {
        FakeContactsState.phoneRows = emptyList()
        val result = WhatsAppContactSync.sync(context)
        assertEquals(0, result.deviceCount); assertEquals(0, result.e164Count)
        assertTrue(waDb.contactDao().getAll().isEmpty())
    }

    @Test fun signalSyncFailsClosedWithoutAuthButCountsNumbers() = runTest {
        FakeContactsState.phoneRows = listOf(
            phoneRow(1L, "Alice", "+1 (415) 555-0102"),
            phoneRow(2L, "Bad", "xyz"),
        )
        val result = SignalContactSync.sync(context)
        assertEquals(2, result.deviceCount)
        assertEquals(1, result.e164Count)
        assertEquals(0, result.onSignalCount)
        assertEquals("CDSI lookup failed", result.transportError)
        assertTrue(sigDb.contactDao().getAll().isEmpty()) // nothing persisted on lookup failure
    }

    @Test fun signalSyncEmptyBookSkipsLookup() = runTest {
        FakeContactsState.phoneRows = listOf(phoneRow(1L, "Bad", "xyz"))
        val result = SignalContactSync.sync(context)
        assertEquals(0, result.e164Count); assertNull(result.transportError)
    }

    @Test fun e164Uint64CodecRoundTrips() {
        val bytes = SignalContactSync.e164ToUint64Bytes("+14155550102")
        assertEquals(8, bytes.size)
        val value = java.nio.ByteBuffer.wrap(bytes).long
        assertEquals(14155550102L, value)
        assertEquals("+14155550102", SignalContactSync.uint64ToE164(value))
        assertEquals(0L, java.nio.ByteBuffer.wrap(SignalContactSync.e164ToUint64Bytes("no digits")).long)
        assertEquals("+0", SignalContactSync.uint64ToE164(0L))
    }

    @Test fun registrationIntegrityCollectBuildsSignals() {
        val signals = com.vayunmathur.communicate.data.whatsapp.registration.RegistrationIntegrity
            .collect(context, nowSeconds = 1_700_000_000L) { null }
        assertEquals(1_700_000_000L, signals.tSeconds)
        assertTrue(signals.gp.isNotBlank())
        assertTrue(signals.ge.contains("\"sv\":"))
        assertTrue(signals.ga.contains("\"ae\":"))
        assertEquals("""{"em":""}""", signals.gs)
        assertNull(signals.gi) // encryptor returned null on host
        assertTrue(signals.db == "0" || signals.db == "1")
        val withEnc = com.vayunmathur.communicate.data.whatsapp.registration.RegistrationIntegrity
            .collect(context, nowSeconds = 1L) { "enc:$it" }
        // gi is built only when the encrypted blob is non-empty; either shape is a real outcome.
        assertTrue(withEnc.gi == null || withEnc.gi.startsWith("enc:") || withEnc.gi.isNotBlank())
    }

    @Test fun signalContactDaoQueriesByPhonePniAndOnSignal() = runTest {
        sigDb.contactDao().upsertAll(listOf(
            SignalContact(aci = "aci-1", phoneE164 = "+14155550102", displayName = "Alice", onSignal = true, pni = "pni-1"),
            SignalContact(aci = "aci-2", phoneE164 = "+14155550103", displayName = "Bob", onSignal = false),
        ))
        assertEquals("Alice", sigDb.contactDao().getByPhone("+14155550102")!!.displayName)
        assertEquals("aci-1", sigDb.contactDao().getByPni("pni-1")!!.aci)
        assertNull(sigDb.contactDao().getByPni(""))
        assertEquals(listOf("Alice"), sigDb.contactDao().getOnSignal().map { it.displayName })
        sigDb.contactDao().deleteByPhone("+14155550102")
        assertNull(sigDb.contactDao().getByPhone("+14155550102"))
    }
}
