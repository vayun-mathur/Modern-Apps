package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Map-backed cursor builder shared by the fake providers below. */
internal fun cursorFor(projection: Array<String>?, rows: List<Map<String, Any?>>): Cursor {
    val cols = projection?.takeIf { it.isNotEmpty() } ?: rows.firstOrNull()?.keys?.toTypedArray() ?: arrayOf("_id")
    val cursor = MatrixCursor(cols, rows.size)
    for (row in rows) cursor.addRow(cols.map { row[it] })
    return cursor
}

internal fun filterRows(
    rows: List<Map<String, Any?>>,
    selection: String?,
    args: Array<String>?,
): List<Map<String, Any?>> {
    if (selection.isNullOrBlank()) return rows
    var result = rows
    // "col = ?" clauses bound from args, in order.
    val boundCols = Regex("""(\w+)\s*=\s*\?""").findAll(selection).map { it.groupValues[1] }.toList()
    boundCols.forEachIndexed { i, col ->
        val arg = args?.getOrNull(i) ?: return@forEachIndexed
        result = result.filter { row -> row[col]?.toString() == arg }
    }
    // Literal "col = 137" clauses.
    for (m in Regex("""(\w+)\s*=\s*(\d+)""").findAll(selection)) {
        val col = m.groupValues[1]
        val value = m.groupValues[2]
        result = result.filter { row -> row[col]?.toString() == value }
    }
    return result
}

/** Telephony/call-log provider state, shared across the sms/mms/mms-sms/call_log authorities. */
object FakeTelephonyState {
    var smsRows: List<Map<String, Any?>> = emptyList()
    var mmsRows: List<Map<String, Any?>> = emptyList()
    var partRows: List<Map<String, Any?>> = emptyList()
    var addrRows: List<Map<String, Any?>> = emptyList()
    var canonicalRows: List<Map<String, Any?>> = emptyList()
    var threadRows: List<Map<String, Any?>> = emptyList()
    var callLogRows: List<Map<String, Any?>> = emptyList()
    var updateResult: Int = 1
    var deleteResult: Int = 1
    val inserts = mutableListOf<Pair<Uri, ContentValues?>>()
    val deletes = mutableListOf<Uri>()
    val updates = mutableListOf<Uri>()

    fun reset() {
        smsRows = emptyList(); mmsRows = emptyList(); partRows = emptyList()
        addrRows = emptyList(); canonicalRows = emptyList(); threadRows = emptyList()
        callLogRows = emptyList(); updateResult = 1; deleteResult = 1
        inserts.clear(); deletes.clear(); updates.clear()
    }
}

open class FakeTelephonyProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val s = uri.toString()
        val rows = when {
            s.contains("canonical-addresses") -> FakeTelephonyState.canonicalRows
            uri.authority == "mms-sms" -> FakeTelephonyState.threadRows
            uri.authority == "call_log" -> FakeTelephonyState.callLogRows
            uri.authority == "sms" -> filterRows(FakeTelephonyState.smsRows, selection, selectionArgs)
            uri.path?.contains("/part") == true ->
                filterRows(FakeTelephonyState.partRows, selection, selectionArgs)
            uri.path?.contains("/addr") == true ->
                filterRows(FakeTelephonyState.addrRows, selection, selectionArgs)
            uri.authority == "mms" -> filterRows(FakeTelephonyState.mmsRows, selection, selectionArgs)
            else -> emptyList()
        }
        return cursorFor(projection, rows)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        FakeTelephonyState.inserts += uri to values
        return uri.buildUpon().appendPath("999").build()
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        FakeTelephonyState.updates += uri
        return FakeTelephonyState.updateResult
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        FakeTelephonyState.deletes += uri
        return FakeTelephonyState.deleteResult
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = File.createTempFile("mms-part", ".txt")
        file.writeText("file-body")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
}

class FakeSmsProvider : FakeTelephonyProvider()
class FakeMmsProvider : FakeTelephonyProvider()
class FakeMmsSmsProvider : FakeTelephonyProvider()
class FakeCallLogProvider : FakeTelephonyProvider()

object FakeContactsState {
    var phoneRows: List<Map<String, Any?>> = emptyList()
    var lookupRows: List<Map<String, Any?>> = emptyList()
    fun reset() { phoneRows = emptyList(); lookupRows = emptyList() }
}

class FakeContactsProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor = cursorFor(
        projection,
        if (uri.toString().contains("phone_lookup")) FakeContactsState.lookupRows else FakeContactsState.phoneRows,
    )

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}

/**
 * W1: SIM provider reads in `CommunicateRepositorySimKt` — contacts, call log,
 * SMS/MMS threads and messages, MMS parts/senders, thread recipients and the
 * contact-name helpers, served by fake providers over [MatrixCursor]s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunicateRepositorySimW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeCallLogProvider::class.java, "call_log")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE,
        )
    }

    private fun smsRow(
        id: Long = 1L, thread: Long = 10L, address: String = "+14155550102", body: String = "hi",
        date: Long = 2_000L, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX, read: Int = 0,
        status: Int = Telephony.Sms.STATUS_NONE, sub: Int = -1,
    ): Map<String, Any?> = mapOf(
        Telephony.Sms._ID to id, Telephony.Sms.THREAD_ID to thread, Telephony.Sms.ADDRESS to address,
        Telephony.Sms.BODY to body, Telephony.Sms.DATE to date, Telephony.Sms.TYPE to type,
        Telephony.Sms.READ to read, Telephony.Sms.STATUS to status, Telephony.Sms.SUBSCRIPTION_ID to sub,
    )

    private fun mmsRow(
        id: Long = 5L, thread: Long = 10L, dateSec: Long = 3L,
        box: Int = Telephony.Mms.MESSAGE_BOX_INBOX, read: Int = 0, sub: Int = -1,
    ): Map<String, Any?> = mapOf(
        Telephony.Mms._ID to id, Telephony.Mms.THREAD_ID to thread, Telephony.Mms.DATE to dateSec,
        Telephony.Mms.MESSAGE_BOX to box, Telephony.Mms.READ to read, Telephony.Mms.SUBSCRIPTION_ID to sub,
    )

    // -- contacts --

    @Test
    fun `loadContacts dedupes normalized numbers and skips blanks`() {
        FakeContactsState.phoneRows = listOf(
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 1L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+1 (415) 555-0102",
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 2L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "Alice Work",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "+14155550102",
                ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_WORK,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 3L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "  ",
                ContactsContract.CommonDataKinds.Phone.TYPE to 0,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
            mapOf(
                ContactsContract.CommonDataKinds.Phone._ID to 4L,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY to "",
                ContactsContract.CommonDataKinds.Phone.NUMBER to "911",
                ContactsContract.CommonDataKinds.Phone.TYPE to 0,
                ContactsContract.CommonDataKinds.Phone.LABEL to null,
            ),
        )
        val contacts = CommunicateRepository.loadContacts(context)
        assertEquals(2, contacts.size)
        assertEquals("Alice", contacts[0].name)
        assertEquals("+1 (415) 555-0102", contacts[0].phoneNumber)
        assertEquals("911", contacts[1].name) // blank display name falls back to the number
    }

    @Test
    fun `loadContacts returns empty without permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsState.phoneRows = listOf(emptyMap())
        assertTrue(CommunicateRepository.loadContacts(context).isEmpty())
    }

    // -- call log --

    @Test
    fun `loadCallLogs maps types and blank numbers`() {
        FakeTelephonyState.callLogRows = listOf(
            mapOf(
                CallLog.Calls._ID to 1L, CallLog.Calls.CACHED_NAME to "Bob",
                CallLog.Calls.NUMBER to "+14155550103", CallLog.Calls.TYPE to CallLog.Calls.INCOMING_TYPE,
                CallLog.Calls.DATE to 5_000L, CallLog.Calls.DURATION to 30L,
                CallLog.Calls.PHONE_ACCOUNT_ID to "99",
            ),
            mapOf(
                CallLog.Calls._ID to 2L, CallLog.Calls.CACHED_NAME to null,
                CallLog.Calls.NUMBER to "", CallLog.Calls.TYPE to CallLog.Calls.MISSED_TYPE,
                CallLog.Calls.DATE to 4_000L, CallLog.Calls.DURATION to 0L,
                CallLog.Calls.PHONE_ACCOUNT_ID to null,
            ),
            mapOf(
                CallLog.Calls._ID to 3L, CallLog.Calls.CACHED_NAME to "  ",
                CallLog.Calls.NUMBER to "123", CallLog.Calls.TYPE to 999,
                CallLog.Calls.DATE to 3_000L, CallLog.Calls.DURATION to 5L,
                CallLog.Calls.PHONE_ACCOUNT_ID to null,
            ),
        )
        val logs = CommunicateRepository.loadCallLogs(context)
        assertEquals(3, logs.size)
        assertEquals(CommunicateCallType.Incoming, logs[0].type)
        assertEquals("Bob", logs[0].displayName)
        assertNull(logs[0].subscriptionId) // 99 is not an active SIM
        assertEquals("Unknown", logs[1].phoneNumber)
        assertEquals(CommunicateCallType.Missed, logs[1].type)
        assertNull(logs[1].displayName)
        assertEquals(CommunicateCallType.Unknown, logs[2].type)
        assertNull(logs[2].displayName)
    }

    @Test
    fun `loadCallLogs returns empty without permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CALL_LOG)
        assertTrue(CommunicateRepository.loadCallLogs(context).isEmpty())
    }

    @Test
    fun `call type mapping covers every provider type`() {
        assertEquals(CommunicateCallType.Incoming, CallLog.Calls.INCOMING_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Outgoing, CallLog.Calls.OUTGOING_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Missed, CallLog.Calls.MISSED_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Rejected, CallLog.Calls.REJECTED_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Blocked, CallLog.Calls.BLOCKED_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Voicemail, CallLog.Calls.VOICEMAIL_TYPE.toCommunicateCallType())
        assertEquals(CommunicateCallType.Unknown, (-1).toCommunicateCallType())
    }

    // -- SMS messages --

    @Test
    fun `loadSmsMessages maps direction read and status`() {
        FakeTelephonyState.smsRows = listOf(
            smsRow(id = 1L, type = Telephony.Sms.MESSAGE_TYPE_SENT, status = Telephony.Sms.STATUS_COMPLETE),
            smsRow(id = 2L, type = Telephony.Sms.MESSAGE_TYPE_FAILED, body = "nope"),
            smsRow(id = 3L, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, status = Telephony.Sms.STATUS_PENDING),
            smsRow(id = 4L, type = Telephony.Sms.MESSAGE_TYPE_INBOX, read = 1, sub = 2),
        )
        val msgs = CommunicateRepository.loadSmsMessages(context, threadId = null)
        assertEquals(4, msgs.size)
        assertTrue(msgs[0].outgoing)
        assertEquals(MessageStatus.Delivered, msgs[0].status)
        assertEquals(MessageStatus.Failed, msgs[1].status)
        assertTrue(msgs[2].outgoing)
        assertEquals(MessageStatus.Sent, msgs[2].status)
        assertFalse(msgs[3].outgoing)
        assertTrue(msgs[3].read)
        assertEquals(MessageStatus.None, msgs[3].status)
        assertEquals(2, msgs[3].subscriptionId)
    }

    @Test
    fun `loadSmsMessages filters by thread and needs permission`() {
        FakeTelephonyState.smsRows = listOf(smsRow(id = 1L, thread = 10L), smsRow(id = 2L, thread = 11L))
        assertEquals(listOf(1L), CommunicateRepository.loadSmsMessages(context, 10L).map { it.id })
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS)
        assertTrue(CommunicateRepository.loadSmsMessages(context, null).isEmpty())
    }

    @Test
    fun `smsStatus precedence failed then delivered then sent`() {
        with(CommunicateRepository) {
            assertEquals(
                MessageStatus.Failed,
                smsStatus(Telephony.Sms.MESSAGE_TYPE_FAILED, outgoing = true, Telephony.Sms.STATUS_COMPLETE))
            assertEquals(
                MessageStatus.None,
                smsStatus(Telephony.Sms.MESSAGE_TYPE_INBOX, outgoing = false, Telephony.Sms.STATUS_COMPLETE))
            assertEquals(
                MessageStatus.Failed,
                smsStatus(Telephony.Sms.MESSAGE_TYPE_SENT, outgoing = true, Telephony.Sms.STATUS_FAILED))
            assertEquals(
                MessageStatus.Sent,
                smsStatus(Telephony.Sms.MESSAGE_TYPE_SENT, outgoing = true, Telephony.Sms.STATUS_NONE))
        }
    }

    // -- MMS messages / parts / sender --

    @Test
    fun `loadMmsMessages joins text image parts and sender`() {
        FakeTelephonyState.mmsRows = listOf(mmsRow(id = 5L), mmsRow(id = 6L, box = Telephony.Mms.MESSAGE_BOX_SENT))
        FakeTelephonyState.partRows = listOf(
            mapOf("_id" to 51L, "ct" to "text/plain", "text" to "mms body", "_data" to null, "mid" to 5L),
            mapOf("_id" to 52L, "ct" to "image/png", "text" to null, "_data" to null, "mid" to 5L),
            mapOf("_id" to 53L, "ct" to "application/smil", "text" to null, "_data" to null, "mid" to 5L),
            mapOf("_id" to 61L, "ct" to "text/plain", "text" to "sent body", "_data" to null, "mid" to 6L),
        )
        FakeTelephonyState.addrRows = listOf(
            mapOf("address" to "+14155550109", "type" to 137),
        )
        val msgs = CommunicateRepository.loadMmsMessages(context, threadId = null)
        assertEquals(2, msgs.size)
        val inbound = msgs[0]
        assertEquals("mms body", inbound.body)
        assertEquals(3_000L, inbound.timestampMillis) // MMS DATE is seconds
        assertEquals("+14155550109", inbound.address)
        assertEquals("+14155550109", inbound.senderAddress)
        assertEquals(1, inbound.attachments.size)
        assertEquals("content://mms/part/52", inbound.attachments[0].contentUri)
        assertEquals("image/png", inbound.attachments[0].mimeType)
        assertTrue(msgs[1].outgoing)
        assertEquals(MessageStatus.Sent, msgs[1].status)
        assertNull(msgs[1].senderAddress)
    }

    @Test
    fun `mms text part stored as file is read through the part uri`() {
        FakeTelephonyState.partRows = listOf(
            mapOf("_id" to 71L, "ct" to "text/plain", "text" to null, "_data" to "/data/part71", "mid" to 7L),
        )
        val (text, attachments) = with(CommunicateRepository) { loadMmsParts(context, 7L) }
        assertEquals("file-body", text)
        assertTrue(attachments.isEmpty())
        assertEquals("file-body", with(CommunicateRepository) { readMmsTextPart(context, 71L) })
    }

    @Test
    fun `loadMmsSender ignores the insert-address token`() {
        FakeTelephonyState.addrRows = listOf(mapOf("address" to "insert-address-token", "type" to 137))
        assertNull(with(CommunicateRepository) { loadMmsSender(context, 5L) })
        FakeTelephonyState.addrRows = listOf(mapOf("address" to "  ", "type" to 137))
        assertNull(with(CommunicateRepository) { loadMmsSender(context, 5L) })
    }

    // -- threads --

    @Test
    fun `loadThreadRecipients resolves canonical addresses`() {
        FakeTelephonyState.canonicalRows = listOf(
            mapOf("_id" to "1", "address" to "+14155550102"),
            mapOf("_id" to "2", "address" to ""),
            mapOf("_id" to "3", "address" to "+14155550103"),
        )
        FakeTelephonyState.threadRows = listOf(
            mapOf(Telephony.Threads._ID to 10L, Telephony.Threads.RECIPIENT_IDS to "1 3"),
            mapOf(Telephony.Threads._ID to 11L, Telephony.Threads.RECIPIENT_IDS to "2"),
        )
        val recipients = with(CommunicateRepository) { loadThreadRecipients(context) }
        assertEquals(listOf("+14155550102", "+14155550103"), recipients[10L])
        assertNull(recipients[11L])
    }

    @Test
    fun `loadSmsThreads merges sms and mms into group and single threads`() {
        FakeTelephonyState.canonicalRows = listOf(
            mapOf("_id" to "1", "address" to "+14155550102"),
            mapOf("_id" to "3", "address" to "+14155550103"),
        )
        FakeTelephonyState.threadRows = listOf(
            mapOf(Telephony.Threads._ID to 10L, Telephony.Threads.RECIPIENT_IDS to "1 3"),
            mapOf(Telephony.Threads._ID to 11L, Telephony.Threads.RECIPIENT_IDS to "1"),
        )
        FakeTelephonyState.smsRows = listOf(
            smsRow(id = 1L, thread = 10L, body = "group latest", date = 9_000L, read = 0),
            smsRow(id = 2L, thread = 10L, body = "group older", date = 8_000L, read = 1),
            smsRow(id = 3L, thread = 11L, body = "single", date = 7_000L, address = "+14155550102"),
        )
        FakeTelephonyState.mmsRows = listOf(mmsRow(id = 5L, thread = 11L, dateSec = 6L))
        FakeTelephonyState.partRows = listOf(
            mapOf("_id" to 61L, "ct" to "text/plain", "text" to "mms single", "_data" to null, "mid" to 5L),
        )
        val threads = CommunicateRepository.loadSmsThreads(context)
        assertEquals(2, threads.size)
        val group = threads.first { it.threadId == 10L }
        assertTrue(group.isGroup)
        assertEquals("group latest", group.snippet)
        assertEquals(1, group.unreadCount)
        assertEquals(listOf("+14155550102", "+14155550103"), group.participants)
        val single = threads.first { it.threadId == 11L }
        assertFalse(single.isGroup)
        assertEquals("+14155550102", single.address)
        assertEquals("single", single.snippet) // newest of SMS (7s) vs MMS (6s)
    }

    @Test
    fun `loadSmsThreads returns empty without sms permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_SMS)
        assertTrue(CommunicateRepository.loadSmsThreads(context).isEmpty())
    }

    // -- contact helpers --

    @Test
    fun `findContactName resolves blanks and misses`() {
        FakeContactsState.lookupRows = listOf(
            mapOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY to "Alice"),
        )
        assertEquals("Alice", CommunicateRepository.findContactName(context, "+14155550102"))
        assertNull(CommunicateRepository.findContactName(context, " "))
        FakeContactsState.lookupRows = emptyList()
        assertNull(CommunicateRepository.findContactName(context, "+14155550102"))
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsState.lookupRows = listOf(
            mapOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY to "Alice"),
        )
        assertNull(CommunicateRepository.findContactName(context, "+14155550102"))
    }

    @Test
    fun `isUnknownContact rejects service ids and known numbers`() {
        assertFalse(CommunicateRepository.isUnknownContact(context, ""))
        assertFalse(CommunicateRepository.isUnknownContact(context, "alice@s.whatsapp.net"))
        assertFalse(CommunicateRepository.isUnknownContact(context, "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"))
        assertFalse(CommunicateRepository.isUnknownContact(context, "service-no-digits"))
        FakeContactsState.lookupRows = listOf(
            mapOf(ContactsContract.PhoneLookup.DISPLAY_NAME_PRIMARY to "Alice"),
        )
        assertFalse(CommunicateRepository.isUnknownContact(context, "+14155550102"))
        FakeContactsState.lookupRows = emptyList()
        assertTrue(CommunicateRepository.isUnknownContact(context, "+14155559999"))
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_CONTACTS)
        assertFalse(CommunicateRepository.isUnknownContact(context, "+14155559999"))
    }

    @Test
    fun `composer intent carries number and body`() {
        CommunicateRepository.openSmsComposer(context, "+14155550102", "hello")
        val intent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto:+14155550102", intent.data.toString())
        assertEquals("hello", intent.getStringExtra("sms_body"))
    }

    @Test
    fun `markSimThreadRead sums provider updates`() = kotlinx.coroutines.test.runTest {
        FakeTelephonyState.updateResult = 2
        assertTrue(CommunicateRepository.markSimThreadRead(context, 10L))
        assertEquals(2, FakeTelephonyState.updates.size) // SMS + MMS tables
        FakeTelephonyState.updateResult = 0
        assertFalse(CommunicateRepository.markSimThreadRead(context, 10L))
    }

    @Test
    fun `group thread id is null for empty recipient sets`() {
        assertNull(CommunicateRepository.getOrCreateSmsGroupThreadId(context, listOf(" ", "")))
        assertNull(CommunicateRepository.getOrCreateSmsGroupThreadId(context, emptyList()))
    }

    @Test
    fun `phone account handle is null for negative or unpermitted subscriptions`() {
        assertNull(with(CommunicateRepository) { phoneAccountHandleForSub(context, -1) })
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertNull(with(CommunicateRepository) { phoneAccountHandleForSub(context, 3) })
    }
}
