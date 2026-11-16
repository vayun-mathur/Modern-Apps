package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.refreshGroup
import com.vayunmathur.communicate.data.signal.setGroupName
import com.vayunmathur.communicate.data.signal.updateGroupParticipants
import com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * W1 wave 4: MMS true-path sends, the uncaptured MEX operation catalog,
 * Signal group-op early returns, and WhatsApp group creation offline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class CommunicateWave4W1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var signalDb: SignalDatabase

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
        )
        signalDb = Room.inMemoryDatabaseBuilder(context, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = signalDb
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671",
            aci = "3f0e5a2c-1111-2222-3333-444455556666",
            password = "secret",
        )
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest
    fun tearDown() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        signalDb.close()
    }

    // -- MMS true path --

    @Test
    fun `sendSimMms sends a group text as MMS and persists it`() {
        val ok = with(CommunicateRepository) {
            sendSimMms(context, -1, listOf("+14155550102", "+14155550103"), "group hi", emptyList())
        }
        assertTrue(ok)
        // The outgoing MMS row + text part + TO addresses were persisted.
        val uris = FakeTelephonyState.inserts.map { it.first.toString() }
        assertTrue(uris.any { it == "content://mms" }, "$uris")
        assertTrue(uris.any { it.contains("/addr") }, "$uris")
    }

    // Note: only one true MMS send per Robolectric sandbox — FileProvider
    // caches its path strategy from the first test's data directory, so a
    // second send in another method fails to resolve the PDU uri. The
    // unified group dispatch shares this same sendSimMms path.

    // -- MEX catalog (uncaptured ops fail typed without any network) --

    @Test
    fun `mex uncaptured operations build variables and fail typed`() = runTest {
        val results = listOf(
            "participating" to WhatsAppMexOps.groupParticipatingGroups(context, "15550001@s.whatsapp.net"),
            "batch" to WhatsAppMexOps.groupBatchQueryById(context, listOf("111@g.us", "222@g.us")),
            "fullSync" to WhatsAppMexOps.primaryContactsFullSync(
                context, listOf("+14155550102"), sessionId = "sess-1", pageIndex = 0, last = true,
            ),
            "usernameCheck" to WhatsAppMexOps.usernameCheck(context, "alice"),
            "blocklistUpdate" to WhatsAppMexOps.updateBlocklistLid(context, "12345@lid", "phone", "+14155550102", "block"),
            "newsletter" to WhatsAppMexOps.newsletter(context, "123@newsletter"),
            "newsletterJoin" to WhatsAppMexOps.newsletterJoinV2(context, "123@newsletter"),
            "newsletterLeave" to WhatsAppMexOps.newsletterLeaveV2(context, "123@newsletter"),
            "getKeys" to WhatsAppMexOps.getMessagingKeys(context, listOf("15550002@s.whatsapp.net")),
            "setKeysNoAuth" to WhatsAppMexOps.setMessagingKeys(context),
        )
        for ((name, result) in results) {
            assertTrue(result.transportError != null, "$name unexpectedly succeeded: $result")
            assertFalse(result.isSuccess, "$name")
        }
        assertEquals("no_auth", results.last().second.transportError)
        assertTrue(
            results.dropLast(1).all { it.second.transportError!!.startsWith("no_persisted_id:") },
            "$results",
        )
    }

    // -- Signal group ops (early returns) --

    @Test
    fun `signal group ops refuse unknown or keyless groups`() = runTest {
        assertFalse(SignalClient.setGroupName("group:missing", "New name"))
        assertFalse(SignalClient.updateGroupParticipants("group:missing", listOf("a"), "add"))
        assertFalse(SignalClient.refreshGroup("group:missing"))
        signalDb.conversationDao().upsert(
            SignalConversation(chatId = "group:keyless", isGroup = true, name = "Old"),
        )
        assertFalse(SignalClient.setGroupName("group:keyless", "New name"))
        assertFalse(SignalClient.updateGroupParticipants("group:keyless", listOf("a"), "add"))
        assertFalse(SignalClient.refreshGroup("group:keyless"))
        // Removal is refused even with a master key (not implemented yet).
        signalDb.conversationDao().upsert(
            SignalConversation(
                chatId = "group:known", isGroup = true, name = "Team",
                groupMasterKey = ByteArray(32) { 1 }, groupRevision = 3,
            ),
        )
        assertFalse(SignalClient.updateGroupParticipants("group:known", listOf("a"), "remove"))
        // The local name is untouched by every refused change.
        assertEquals("Old", signalDb.conversationDao().getConversation("group:keyless")!!.name)
        assertEquals("Team", signalDb.conversationDao().getConversation("group:known")!!.name)
    }

    @Test
    fun `signal group ops refuse without auth`() = runTest {
        SignalClient.authData = null
        assertFalse(SignalClient.refreshGroup("group:known"))
    }

    // -- WhatsApp group creation offline --

    @Test
    fun `createWhatsAppGroup needs at least one valid JID`() = runTest {
        assertNull(CommunicateRepository.createWhatsAppGroup(context, "Team", emptyList()))
        assertNull(CommunicateRepository.createWhatsAppGroup(context, "Team", listOf(" ", "group:1")))
    }
}
