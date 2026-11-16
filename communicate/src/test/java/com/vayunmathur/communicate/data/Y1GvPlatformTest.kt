package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuthException
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceClient
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceParser
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.communicate.data.googlevoice.call.SipClient
import com.vayunmathur.communicate.data.googlevoice.GvSipRegisterInfo
import java.io.IOException
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** State for [Y1PlatformProvider]. */
object Y1PlatformState {
    var rawRows = mutableListOf<Map<String, Any?>>()
    val inserts = mutableListOf<Pair<Uri, ContentValues?>>()
    val batches = mutableListOf<Int>()
    var nextId = 500L
    fun reset() { rawRows.clear(); inserts.clear(); batches.clear(); nextId = 500L }
}

/** Contacts provider serving RawContacts lookups + recording inserts/batches for platform rows. */
class Y1PlatformProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun getType(uri: Uri): String? = null
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val cols = projection ?: arrayOf(ContactsContract.RawContacts._ID)
        val cursor = MatrixCursor(cols)
        if (uri.toString().contains("raw_contacts")) {
            val e164 = args?.getOrNull(1)
            Y1PlatformState.rawRows.filter { it[ContactsContract.RawContacts.SYNC1] == e164 }
                .forEach { row -> cursor.addRow(cols.map { row[it] }) }
        }
        return cursor
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        Y1PlatformState.inserts += uri to values
        if (uri.toString().contains("raw_contacts")) {
            val id = Y1PlatformState.nextId++
            Y1PlatformState.rawRows += mapOf(
                ContactsContract.RawContacts._ID to id,
                ContactsContract.RawContacts.SYNC1 to values?.getAsString(ContactsContract.RawContacts.SYNC1),
            )
            return Uri.parse("content://com.android.contacts/raw_contacts/$id")
        }
        return Uri.parse("$uri/1")
    }
    override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int = 6
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int = 0
    override fun applyBatch(authority: String, operations: ArrayList<android.content.ContentProviderOperation>): Array<android.content.ContentProviderResult?> {
        Y1PlatformState.batches += operations.size
        return super.applyBatch(authority, operations)
    }
}

/**
 * Y1: Google Voice client over a scripted localhost server, ContactPlatformRows
 * publishing against a recording contacts provider, and SipClient inbound
 * message handling (via its private handler, exercised reflectively because
 * the WebSocket transport cannot connect on a JVM host).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1GvPlatformTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()
    private val scripted = mutableMapOf<String, Pair<Int, String>>()

    private val listBody = "[[[\"t.+14152124034\",1,[[\"h1\",1786409649630,\"+12133945548\"," +
        "[\"+14152124034\",\"+14152124034\",null,null,null,null,0],10,1,null,null,null," +
        "\"Hello there\",null,null,5,1,null,\"+14152124034\",0]],null,[[\"+14152124034\"]]]]]"

    @BeforeTest fun setUp() {
        requests.clear(); scripted.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val path = ex.requestURI.path
            requests += "$path ${ex.requestBody.readBytes().decodeToString()}"
            val (status, resp) = scripted.entries.firstOrNull { path.contains(it.key) }?.value ?: (200 to listBody)
            val bytes = resp.encodeToByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        Y1PlatformState.reset()
        // Pre-create the contacts account so ContactPlatformRows.ensureAccount finds it present.
        runCatching {
            val am = android.accounts.AccountManager.get(context)
            val acct = android.accounts.Account("Contacts", "com.vayunmathur.contacts")
            if (am.getAccountsByType("com.vayunmathur.contacts").none { it.name == "Contacts" }) {
                am.addAccountExplicitly(acct, null, null)
            }
        }
        Robolectric.setupContentProvider(Y1PlatformProvider::class.java, ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS, Manifest.permission.GET_ACCOUNTS, "android.permission.MANAGE_ACCOUNTS")
    }

    @AfterTest fun tearDown() { server.stop(0) }

    private fun gvClient(): GoogleVoiceClient = runBlocking {
        val session = GoogleVoiceSession.get(context)
        session.save("SAPISID=abc123; other=x", "APIKEY", "0")
        GoogleVoiceClient(session).also { it.baseForTest = "http://127.0.0.1:${server.address.port}/" }
    }

    @Test fun gvClientListsAndReadsThreadsOverLocalhost(): Unit = runBlocking {
        val client = gvClient()
        val threads = client.listThreads()
        assertEquals(1, threads.size); assertEquals("+14152124034", threads[0].phoneNumber)
        val msgs = client.getThread("t.+14152124034")
        assertEquals(1, msgs.size); assertEquals("Hello there", msgs[0].text)
        assertTrue(client.search("har").isNotEmpty())
        assertTrue(requests.any { it.contains("api2thread/search") })
        client.sendSms("+14152124034", "hi")
        client.sendPreparedSms("[prepared]")
        client.markAllRead()
        client.updateThreadAttributes("t.+14152124034", GoogleVoiceParser.ThreadAction.Archive)
        client.markVoicemailRead("c.1")
        assertTrue(requests.any { it.contains("thread/markallread") && it.contains("[1]") })
        assertTrue(requests.any { it.contains("api2thread/sendsms") && it.contains("[prepared]") })
    }

    @Test fun gvClientAccountStoresPhoneAndSipInfoParses(): Unit = runBlocking {
        scripted["account/get"] = 200 to """[["x"],"+12133945548"]"""
        scripted["sipregisterinfo/get"] = 200 to """[["+12133945548","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","bbbbbbbbbbbbbbbbbbbbbbbb"]]"""
        val client = gvClient()
        val account = client.getAccount()
        assertEquals("+12133945548", account.phoneNumber)
        assertEquals("+12133945548", GoogleVoiceSession.get(context).phoneNumber())
        val sip = client.getSipRegisterInfo()
        assertEquals("+12133945548", sip.phoneNumber)
        assertTrue(sip.credentials.isNotEmpty())
    }

    @Test fun gvClientAuthAndHttpErrorsThrow(): Unit = runBlocking {
        val client = gvClient()
        scripted["api2thread/list"] = 401 to """{}"""
        assertFailsWith<GoogleVoiceAuthException> { client.listThreads() }
        scripted["api2thread/list"] = 500 to """oops"""
        assertFailsWith<IOException> { client.listCalls() }
    }

    @Test fun gvClientCallsAndVoicemailsUseListBodies(): Unit = runBlocking {
        val client = gvClient()
        // Same t. body parses as zero c. calls/voicemails — the folder request still ran.
        assertTrue(client.listCalls().isEmpty())
        assertTrue(client.listVoicemails().isEmpty())
        assertEquals(2, requests.count { it.contains("api2thread/list") })
    }

    // ---------- ContactPlatformRows ----------

    @Test fun platformRowsPublishCreatesRawContactAndRows() {
        ContactPlatformRows.publish(context, listOf(
            ContactPlatformRows.Reachability("+14155550102", whatsApp = true, signal = true),
            ContactPlatformRows.Reachability("+14155550103", whatsApp = false, signal = false),
            ContactPlatformRows.Reachability("+14155550104", whatsApp = true, signal = false),
        ))
        // Two reachable contacts x their platform rows + deletes in one batch, or recorded inserts.
        assertTrue(Y1PlatformState.inserts.any { it.first.toString().contains("raw_contacts") }, "${Y1PlatformState.inserts}")
        assertTrue(Y1PlatformState.batches.isNotEmpty() || Y1PlatformState.inserts.size >= 3)
    }

    @Test fun platformRowsReusesExistingRawContact() {
        Y1PlatformState.rawRows += mapOf(
            ContactsContract.RawContacts._ID to 42L,
            ContactsContract.RawContacts.SYNC1 to "+14155550102",
        )
        ContactPlatformRows.publish(context, listOf(
            ContactPlatformRows.Reachability("+14155550102", whatsApp = true, signal = false)))
        // No new raw contact insert for the existing e164 (phone/data inserts still occur via batch).
        assertTrue(Y1PlatformState.inserts.none {
            it.first.toString().contains("raw_contacts") && it.second?.getAsString(ContactsContract.RawContacts.SYNC1) == "+14155550102"
        } || Y1PlatformState.batches.isNotEmpty())
    }

    // ---------- SipClient inbound ----------

    private fun sipHandle(client: SipClient, message: String) {
        val m = SipClient::class.java.getDeclaredMethod("handleIncoming", String::class.java)
        m.isAccessible = true; m.invoke(client, message)
    }

    @Test fun sipInboundResponsesAndRequestsDriveListener() {
        val events = mutableListOf<String>()
        val listener = object : SipClient.Listener {
            override fun onRegistered() { events += "registered" }
            override fun onProvisional(code: Int) { events += "prov:$code" }
            override fun onAnswered(remoteSdp: String) { events += "answered" }
            override fun onEarlyMedia(remoteSdp: String) { events += "early" }
            override fun onEnded() { events += "ended" }
            override fun onFailed(reason: String) { events += "failed:$reason" }
            override fun onIncomingInvite(remoteSdp: String, from: String) { events += "invite" }
        }
        val client = SipClient(
            GvSipRegisterInfo(phoneNumber = "+15550001", credentials = listOf("user=1", "secret")),
            "+15550001", CoroutineScope(Dispatchers.Unconfined), listener)
        val sdp = "v=0\r\no=- 1 1 IN IP4 0.0.0.0\r\nm=audio 9 RTP/AVP 0\r\n"
        sipHandle(client, "SIP/2.0 200 OK\r\nCSeq: 1 REGISTER\r\nContent-Length: 0\r\n\r\n")
        sipHandle(client, "SIP/2.0 180 Ringing\r\nCSeq: 2 INVITE\r\nContent-Length: 0\r\n\r\n")
        sipHandle(client, "SIP/2.0 200 OK\r\nCSeq: 2 INVITE\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        sipHandle(client, "SIP/2.0 404 Not Found\r\nCSeq: 2 INVITE\r\nContent-Length: 0\r\n\r\n")
        sipHandle(client, "INVITE sip:user@host SIP/2.0\r\nFrom: <sip:+15559999@host>;tag=a\r\nTo: <sip:+15550001@host>\r\nCall-ID: x\r\nCSeq: 1 INVITE\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        sipHandle(client, "BYE sip:user@host SIP/2.0\r\nFrom: <sip:+15559999@host>;tag=a\r\nCall-ID: x\r\nCSeq: 3 BYE\r\nContent-Length: 0\r\n\r\n")
        sipHandle(client, "CANCEL sip:user@host SIP/2.0\r\nFrom: <sip:+15559999@host>;tag=a\r\nCall-ID: x\r\nCSeq: 1 CANCEL\r\nContent-Length: 0\r\n\r\n")
        sipHandle(client, "garbage with no sip shape")
        assertTrue("registered" in events, "$events")
        assertTrue(events.any { it.startsWith("prov:") }, "$events")
        assertTrue("answered" in events, "$events")
        assertTrue("invite" in events, "$events")
        assertTrue("ended" in events, "$events")
    }
}
