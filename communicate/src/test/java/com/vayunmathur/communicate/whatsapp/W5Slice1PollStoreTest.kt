package com.vayunmathur.communicate.whatsapp

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.loadPollSecret
import com.vayunmathur.communicate.data.whatsapp.rememberInboundPoll
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.storePollOptions
import com.vayunmathur.communicate.data.whatsapp.storePollSecret
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppClient poll persistence (WhatsAppClientSync.kt) against a
 * real in-memory database wired to the client singleton's [db] slot.
 * Robolectric (Room + Android Log). Asserts option-hash rows, secret hex
 * round-trip through DB + memory cache, and invalid-hex tolerance.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1PollStoreTest {

    private lateinit var db: WhatsAppDatabase

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WhatsAppDatabase::class.java,
        ).allowMainThreadQueries().build()
        WhatsAppClient.db = db
        WhatsAppClient.pollSecrets.clear()
    }

    @AfterTest
    fun close() {
        WhatsAppClient.db = null
        WhatsAppClient.pollSecrets.clear()
        db.close()
    }

    @Test
    fun storePollOptions_writesSha256Rows() = runTest {
        WhatsAppClient.storePollOptions("P1", listOf("Alpha", "Beta"))
        val rows = db.pollOptionDao().getByMessageId("P1")
        assertEquals(2, rows.size)
        val byName = rows.associateBy { it.optionName }
        assertEquals(
            com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
                .sha256("Alpha".toByteArray()).joinToString("") { "%02x".format(it) },
            byName["Alpha"]!!.optionHash,
        )
        assertEquals("Beta", db.pollOptionDao().getByHash("P1", byName["Beta"]!!.optionHash)!!.optionName)
    }

    @Test
    fun pollSecret_memoryAndDbRoundTrip() = runTest {
        val secret = ByteArray(32) { it.toByte() }
        WhatsAppClient.storePollSecret("P2", secret)
        // memory cache hit
        assertTrue(WhatsAppClient.loadPollSecret("P2")!!.contentEquals(secret))
        // evict memory -> DB hex fallback repopulates memory
        WhatsAppClient.pollSecrets.clear()
        assertTrue(WhatsAppClient.loadPollSecret("P2")!!.contentEquals(secret))
        assertEquals(
            secret.joinToString("") { "%02x".format(it) },
            db.pollSecretDao().get("P2"),
        )
    }

    @Test
    fun loadPollSecret_missing_returnsNull() = runTest {
        assertNull(WhatsAppClient.loadPollSecret("nope"))
    }

    @Test
    fun storePollOptions_noDb_isNoOp() = runTest {
        WhatsAppClient.db = null
        WhatsAppClient.storePollOptions("PX", listOf("a"))
        WhatsAppClient.storePollSecret("PX", byteArrayOf(1))
        // memory path still works without db
        assertTrue(WhatsAppClient.loadPollSecret("PX")!!.contentEquals(byteArrayOf(1)))
        WhatsAppClient.db = db
    }

    @Test
    fun rememberInboundPoll_storesOptionsAndSecret() = runTest {
        val secret = ByteArray(32) { 9 }
        val poll = WhatsAppE2EProto.PollCreationMessage.newBuilder()
            .setName("Q?")
            .addOptions(WhatsAppE2EProto.PollCreationMessage.Option.newBuilder().setOptionName("x"))
            .build()
        val e2e = WhatsAppE2EProto.Message.newBuilder()
            .setPollCreationMessageV3(poll)
            .setMessageContextInfo(
                WhatsAppE2EProto.MessageContextInfo.newBuilder()
                    .setMessageSecret(com.google.protobuf.ByteString.copyFrom(secret)),
            )
            .build()
        val msg = com.vayunmathur.communicate.data.whatsapp.WhatsAppMessage(
            id = "RP1", from = "1@g.us", to = "", body = "", timestamp = 1, type = "text",
            pollData = com.vayunmathur.communicate.data.whatsapp.PollData(
                question = "Q?", options = listOf("x"), selectableOptionCount = 1,
            ),
            e2eMessage = e2e,
        )
        WhatsAppClient.rememberInboundPoll(msg)
        assertEquals(1, db.pollOptionDao().getByMessageId("RP1").size)
        assertTrue(WhatsAppClient.loadPollSecret("RP1")!!.contentEquals(secret))
    }
}
