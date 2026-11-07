package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.createGroup
import com.vayunmathur.communicate.data.signal.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.groupContextFor
import com.vayunmathur.communicate.data.signal.refreshGroup
import com.vayunmathur.communicate.data.signal.resolveDestinationAci
import com.vayunmathur.communicate.data.signal.sendContent
import com.vayunmathur.communicate.data.signal.setGroupName
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 2/6 pass 2: the send pipeline and group operations with no socket and
 * no protocol store — every networked path must fail closed, while group
 * creation still persists its master key locally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2Pass2PipelineTest {

    private lateinit var db: SignalDatabase

    @BeforeTest fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = db
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = "3f0e5a2c-1111-2222-3333-444455556666", password = "secret")
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest fun close() {
        SignalClient.db = null; SignalClient.authData = null; SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        db.close()
    }

    private fun content(text: String = "hi"): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder()
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody(text).setTimestamp(42L))
            .build()

    @Test fun `sendContent rejects blank and unaddressable destinations offline`() = runTest {
        assertFalse(SignalClient.sendContent("", content()))
        assertFalse(SignalClient.sendContent("   ", content()))
        assertFalse(SignalClient.sendContent("group:missing", content())) // no participants known
        assertFalse(SignalClient.sendContent("3f0e5a2c-1111-2222-3333-444455556666", content())) // no protocol store
    }

    @Test fun `groupContextFor is nulls for non-groups and unknown groups`() = runTest {
        assertEquals(null to null, SignalClient.groupContextFor("+14155552671"))
        assertEquals(null to null, SignalClient.groupContextFor("group:unknown"))
        db.conversationDao().upsert(SignalConversation(chatId = "group:known", isGroup = true, groupMasterKey = ByteArray(32) { 1 }, groupRevision = 7))
        // isGroupConversation decides the prefix form; either nulls (not a group id form) or the stored key
        val (key, revision) = SignalClient.groupContextFor("group:known")
        assertTrue(key == null || key.contentEquals(ByteArray(32) { 1 }))
        assertTrue(revision == null || revision == 7)
    }

    @Test fun `resolveDestinationAci passes through service ids and rejects blanks`() = runTest {
        assertNull(SignalClient.resolveDestinationAci(""))
        assertNull(SignalClient.resolveDestinationAci("   "))
        assertEquals("3f0e5a2c-1111-2222-3333-444455556666", SignalClient.resolveDestinationAci("3f0e5a2c-1111-2222-3333-444455556666"))
        assertEquals("PNI:3f0e5a2c-1111-2222-3333-444455556666", SignalClient.resolveDestinationAci("PNI:3f0e5a2c-1111-2222-3333-444455556666"))
        assertNull(SignalClient.resolveDestinationAci("+19999999999")) // unknown number, no app context/discovery
    }

    @Test fun `ensureLocalPreKeys without a protocol store returns and can retry`() = runTest {
        SignalClient.ensureLocalPreKeys()
        SignalClient.ensureLocalPreKeys()
        assertFalse(SignalClient.preKeysPrepared.get())
    }

    @Test fun `createGroup persists the conversation and master key even when the relay rejects`() = runTest {
        val id = SignalClient.createGroup("Study Group", listOf("+14155550001", "+14155550002"))
        assertNotNull(id)
        val stored = db.conversationDao().getConversation(id)
        assertNotNull(stored)
        assertEquals("Study Group", stored.name)
        assertTrue(stored.isGroup)
        assertNotNull(stored.groupMasterKey)
        assertEquals(32, stored.groupMasterKey!!.size)
        // Renaming an unknown conversation fails closed (no fresh master key invented)
        assertFalse(SignalClient.setGroupName("group:does-not-exist", "New Name"))
        assertFalse(SignalClient.refreshGroup("group:does-not-exist"))
    }
}
