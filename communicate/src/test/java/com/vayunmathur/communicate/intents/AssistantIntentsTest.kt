package com.vayunmathur.communicate.intents

import android.content.Context
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.LineChoice
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.SmsThread
import com.vayunmathur.library.intents.communicate.FindConversationsQuery
import com.vayunmathur.library.intents.communicate.GetMessagesQuery
import com.vayunmathur.library.intents.communicate.SendMessageRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assistant-tool ([FindConversationsIntent]/[GetMessagesIntent]/[SendMessageIntent])
 * provider logic against a fake [CommunicateAssistantBackend] installed per test,
 * one fake behaviour per line — mirroring todo's AssistantIntentsTest shape.
 * Telephony/network/repository internals are not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantIntentsTest {

    private class FakeBackend(
        val threads: List<SmsThread> = emptyList(),
        val messages: Map<String, List<SmsMessage>> = emptyMap(),
        var sendResult: Boolean = true,
        var sendFailure: Throwable? = null,
    ) : CommunicateAssistantBackend {
        data class SentCall(
            val category: CommunicateLine,
            val address: String,
            val body: String,
            val threadRemoteId: String?,
        )

        val sentCalls = mutableListOf<SentCall>()

        override suspend fun loadThreads(context: Context): List<SmsThread> = threads

        override suspend fun loadMessages(context: Context, thread: SmsThread): List<SmsMessage> =
            messages[thread.threadKey()].orEmpty()

        override suspend fun send(
            context: Context,
            choice: LineChoice,
            address: String,
            body: String,
            threadRemoteId: String?,
        ): Boolean {
            sendFailure?.let { throw it }
            sentCalls += SentCall(choice.category, address, body, threadRemoteId)
            return sendResult
        }
    }

    private fun thread(
        line: CommunicateLine,
        threadId: Long = 1L,
        remoteId: String? = null,
        address: String = "+14155550101",
        displayName: String? = null,
        snippet: String = "",
        isGroup: Boolean = false,
        timestamp: Long = 0L,
    ) = SmsThread(
        threadId = threadId,
        address = address,
        displayName = displayName,
        snippet = snippet,
        timestampMillis = timestamp,
        unreadCount = 0,
        line = line,
        remoteId = remoteId,
        isGroup = isGroup,
    )

    private fun message(body: String, ts: Long, outgoing: Boolean = false, line: CommunicateLine = CommunicateLine.Sim) =
        SmsMessage(
            id = ts,
            threadId = 1L,
            address = "+14155550101",
            body = body,
            timestampMillis = ts,
            outgoing = outgoing,
            read = true,
            line = line,
        )

    private val simThread = thread(
        CommunicateLine.Sim, threadId = 42, address = "+14155550101",
        displayName = "Alice", snippet = "see you soon", timestamp = 300,
    )
    private val waThread = thread(
        CommunicateLine.WhatsApp, threadId = 7, remoteId = "14155550102@s.whatsapp.net",
        address = "+14155550102", displayName = "Bob", snippet = "photo", timestamp = 200,
    )
    private val waGroup = thread(
        CommunicateLine.WhatsApp, threadId = 8, remoteId = "120207@g.us",
        address = "120207@g.us", displayName = "Family", snippet = "dinner?", isGroup = true, timestamp = 150,
    )
    private val gvThread = thread(
        CommunicateLine.GoogleVoice, threadId = 9, remoteId = "t.abc123",
        address = "+14155550103", displayName = "Carol", snippet = "gv hello", timestamp = 100,
    )

    private var fake = FakeBackend()

    @BeforeTest
    fun setUp() {
        fake = FakeBackend(threads = listOf(simThread, waThread, waGroup, gvThread))
        communicateAssistantBackendForTest = fake
    }

    @AfterTest
    fun tearDown() {
        communicateAssistantBackendForTest = null
    }

    // --- FindConversationsIntent ---

    @Test
    fun findBlankQueryReturnsAllWithThreadKeys() = runBlocking {
        val out = FindConversationsIntent().performCalculation(FindConversationsQuery())
        assertEquals(
            listOf("Sim:42", "WhatsApp:14155550102@s.whatsapp.net", "WhatsApp:120207@g.us", "GoogleVoice:t.abc123"),
            out.map { it.threadKey },
        )
        assertEquals("Alice", out[0].displayName)
        assertEquals("+14155550101", out[0].address)
        assertEquals("see you soon", out[0].snippet)
        assertEquals("Sim", out[0].line)
        assertFalse(out[0].isGroup)
        assertTrue(out[2].isGroup)
        assertEquals("WhatsApp", out[2].line)
    }

    @Test
    fun findQueryFiltersNameAddressAndSnippetCaseInsensitively() = runBlocking {
        val byName = FindConversationsIntent().performCalculation(FindConversationsQuery(query = "alice"))
        assertEquals(listOf("Sim:42"), byName.map { it.threadKey })
        val byAddress = FindConversationsIntent().performCalculation(FindConversationsQuery(query = "5550102"))
        assertEquals(listOf("WhatsApp:14155550102@s.whatsapp.net"), byAddress.map { it.threadKey })
        val bySnippet = FindConversationsIntent().performCalculation(FindConversationsQuery(query = "DINNER"))
        assertEquals(listOf("WhatsApp:120207@g.us"), bySnippet.map { it.threadKey })
        assertTrue(
            FindConversationsIntent().performCalculation(FindConversationsQuery(query = "nobody")).isEmpty(),
        )
    }

    @Test
    fun findLimitCapsAndNonPositiveDefaults() = runBlocking {
        assertEquals(
            2,
            FindConversationsIntent().performCalculation(FindConversationsQuery(limit = 2)).size,
        )
        // limit <= 0 falls back to the DTO default window (5), so all 4 seeded rows return.
        assertEquals(
            4,
            FindConversationsIntent().performCalculation(FindConversationsQuery(limit = 0)).size,
        )
        assertEquals(
            4,
            FindConversationsIntent().performCalculation(FindConversationsQuery(limit = 500)).size,
        )
    }

    // --- GetMessagesIntent ---

    @Test
    fun getMessagesReturnsMostRecentWindowOldestFirst() = runBlocking {
        fake = FakeBackend(
            threads = listOf(simThread),
            messages = mapOf(
                "Sim:42" to listOf(
                    message("one", 1), message("two", 2, outgoing = true), message("three", 3),
                ),
            ),
        ).also { communicateAssistantBackendForTest = it }
        val out = GetMessagesIntent().performCalculation(GetMessagesQuery(threadKey = "Sim:42", limit = 2))
        assertEquals(listOf("two", "three"), out.map { it.body })
        assertTrue(out[0].outgoing)
        assertEquals("Sim", out[0].line)
        assertEquals(2L, out[0].timestampMillis)
        val all = GetMessagesIntent().performCalculation(GetMessagesQuery(threadKey = "Sim:42"))
        assertEquals(listOf("one", "two", "three"), all.map { it.body })
    }

    @Test
    fun getMessagesUnknownOrMalformedKeyReturnsEmpty() = runBlocking {
        assertTrue(GetMessagesIntent().performCalculation(GetMessagesQuery(threadKey = "Sim:999")).isEmpty())
        assertTrue(GetMessagesIntent().performCalculation(GetMessagesQuery(threadKey = "not-a-key")).isEmpty())
        assertTrue(GetMessagesIntent().performCalculation(GetMessagesQuery(threadKey = "")).isEmpty())
        // Remote-id keys resolve too (WhatsApp JID contains no extra colon handling surprises).
        assertTrue(
            GetMessagesIntent().performCalculation(
                GetMessagesQuery(threadKey = "WhatsApp:14155550102@s.whatsapp.net"),
            ).isEmpty(),
        )
    }

    // --- SendMessageIntent: one case per line ---

    @Test
    fun sendOnSimRcsWhatsAppSignalDispatchesPerLine() = runBlocking {
        for ((rawLine, expected) in listOf(
            "Sim" to CommunicateLine.Sim,
            "Rcs" to CommunicateLine.Rcs,
            "WhatsApp" to CommunicateLine.WhatsApp,
            "Signal" to CommunicateLine.Signal,
        )) {
            fake.sentCalls.clear()
            val result = SendMessageIntent().performCalculation(
                SendMessageRequest(address = "+14155550101", line = rawLine, body = "hello"),
            )
            assertTrue(result.success, "expected success for $rawLine, got $result")
            assertNull(result.error)
            assertEquals(1, fake.sentCalls.size)
            assertEquals(expected, fake.sentCalls.single().category)
            assertEquals("+14155550101", fake.sentCalls.single().address)
            assertEquals("hello", fake.sentCalls.single().body)
        }
        // Line names parse case-insensitively and threadRemoteId passes through.
        fake.sentCalls.clear()
        val wa = SendMessageIntent().performCalculation(
            SendMessageRequest(
                address = "+14155550102", line = "whatsapp", body = "hi",
                threadRemoteId = "14155550102@s.whatsapp.net",
            ),
        )
        assertTrue(wa.success)
        assertEquals("14155550102@s.whatsapp.net", fake.sentCalls.single().threadRemoteId)
    }

    @Test
    fun sendOnGoogleVoiceReturnsTypedErrorAndNeverDispatches() = runBlocking {
        val result = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "+14155550103", line = "GoogleVoice", body = "hello"),
        )
        assertFalse(result.success)
        assertEquals(ERROR_GOOGLE_VOICE_REQUIRES_ACTIVITY, result.error)
        assertTrue(fake.sentCalls.isEmpty(), "GV must be excluded before the repository is touched")
    }

    @Test
    fun sendValidatesAddressBodyAndLineProviderSide() = runBlocking {
        val noAddress = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "  ", line = "Sim", body = "hi"),
        )
        assertEquals(ERROR_INVALID_ADDRESS, noAddress.error)
        val noBody = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "+14155550101", line = "Sim", body = " "),
        )
        assertEquals(ERROR_INVALID_BODY, noBody.error)
        val badLine = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "+14155550101", line = "Telegram", body = "hi"),
        )
        assertEquals(ERROR_UNKNOWN_LINE, badLine.error)
        assertFalse(noAddress.success)
        assertFalse(noBody.success)
        assertFalse(badLine.success)
        assertTrue(fake.sentCalls.isEmpty())
    }

    @Test
    fun sendFailureAndExceptionBecomeTypedSendFailed() = runBlocking {
        fake.sendResult = false
        val failed = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "+14155550101", line = "Signal", body = "hi"),
        )
        assertEquals(false to ERROR_SEND_FAILED, failed.toPair())
        fake.sendFailure = RuntimeException("boom")
        val crashed = SendMessageIntent().performCalculation(
            SendMessageRequest(address = "+14155550101", line = "Signal", body = "hi"),
        )
        assertFalse(crashed.success)
        assertEquals(ERROR_SEND_FAILED, crashed.error)
    }

    // --- DTO compat ---

    @Test
    fun dtoDefaultsDecodeFromEmptyJson() {
        val send = Json.decodeFromString(SendMessageRequest.serializer(), "{}")
        assertEquals(SendMessageRequest(), send)
        val find = Json.decodeFromString(FindConversationsQuery.serializer(), "{}")
        assertEquals("", find.query)
        assertEquals(5, find.limit)
        val get = Json.decodeFromString(GetMessagesQuery.serializer(), "{}")
        assertEquals(10, get.limit)
    }

    private fun com.vayunmathur.library.intents.communicate.SendMessageResult.toPair() =
        success to error
}
