package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.googlevoice.GvCall
import com.vayunmathur.communicate.data.googlevoice.GvCallType
import com.vayunmathur.communicate.data.googlevoice.GvMessage
import com.vayunmathur.communicate.data.googlevoice.GvThread
import com.vayunmathur.communicate.data.googlevoice.GvVoicemail
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * W1: send dispatch (`CommunicateRepositorySendKt`), merged views and delete
 * routing (`CommunicateRepositoryMergedKt`), plus the pure line-capability
 * and JID/recipient builders in the WhatsApp/Signal repository files.
 */
/** SmsManager shadow adding the one method Robolectric leaves unimplemented. */
@org.robolectric.annotation.Implements(android.telephony.SmsManager::class)
class W1SmsManagerShadow : org.robolectric.shadows.ShadowSmsManager() {
    @org.robolectric.annotation.Implementation
    fun divideMessage(text: String?): java.util.ArrayList<String> =
        java.util.ArrayList((text.orEmpty().ifEmpty { "" }).chunked(50))
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [W1SmsManagerShadow::class])
class CommunicateRepositoryDispatchW1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun setUp() {
        FakeTelephonyState.reset()
        FakeContactsState.reset()
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "mms")
        Robolectric.setupContentProvider(FakeMmsSmsProvider::class.java, "mms-sms")
        Robolectric.setupContentProvider(FakeCallLogProvider::class.java, "call_log")
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, android.provider.ContactsContract.AUTHORITY)
        shadowOf(context as Application).grantPermissions(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_SMS,
        )
    }

    // -- stableThreadId --

    @Test
    fun `stableThreadId sets the flag and is deterministic`() {
        val a = CommunicateRepository.stableThreadId("123@s.whatsapp.net")
        assertEquals(a, CommunicateRepository.stableThreadId("123@s.whatsapp.net"))
        assertTrue(a >= 0x1_0000_0000L)
        assertTrue(CommunicateRepository.stableThreadId("") >= 0x1_0000_0000L)
        assertTrue(
            CommunicateRepository.stableThreadId("a") != CommunicateRepository.stableThreadId("b"),
        )
    }

    // -- line capabilities (pure) --

    @Test
    fun `line capability matrix`() {
        with(CommunicateRepository) {
            assertEquals(
                setOf(
                    CommunicateLine.GoogleVoice, CommunicateLine.WhatsApp,
                    CommunicateLine.Signal, CommunicateLine.Rcs,
                ),
                isVirtualLine,
            )
            assertTrue(canPlaceCall(CommunicateLine.Sim))
            assertTrue(canPlaceCall(CommunicateLine.GoogleVoice))
            assertTrue(canPlaceCall(CommunicateLine.WhatsApp))
            assertTrue(canPlaceCall(CommunicateLine.Signal))
            assertFalse(canPlaceCall(CommunicateLine.Rcs))
            assertFalse(canPlaceVideoCall(CommunicateLine.Sim))
            assertFalse(canPlaceVideoCall(CommunicateLine.GoogleVoice))
            assertTrue(canPlaceVideoCall(CommunicateLine.WhatsApp))
            assertTrue(canPlaceVideoCall(CommunicateLine.Signal))
            assertFalse(canPlaceVideoCall(CommunicateLine.Rcs))
            assertFalse(canPlaceGroupCall(CommunicateLine.WhatsApp))
            assertTrue(canPlaceGroupCall(CommunicateLine.Signal))
            assertFalse(canPlaceGroupCall(CommunicateLine.Rcs))
            assertFalse(canSendPoll(CommunicateLine.Sim))
            assertFalse(canSendPoll(CommunicateLine.GoogleVoice))
            assertTrue(canSendPoll(CommunicateLine.WhatsApp))
            assertTrue(canSendPoll(CommunicateLine.Signal))
            assertTrue(canSendPoll(CommunicateLine.Rcs))
            assertFalse(canShareContact(CommunicateLine.Sim))
            assertFalse(canShareContact(CommunicateLine.GoogleVoice))
            assertTrue(canShareContact(CommunicateLine.WhatsApp))
            assertTrue(canShareContact(CommunicateLine.Signal))
            assertTrue(canShareContact(CommunicateLine.Rcs))
            assertFalse(isWhatsAppConnected())
            assertFalse(isSignalConnected())
        }
    }

    @Test
    fun `placeCall ignores blanks and dials without call permission`() {
        CommunicateRepository.placeCall(context, "  ")
        assertNull(shadowOf(context as Application).nextStartedActivity)
        shadowOf(context as Application).denyPermissions(Manifest.permission.CALL_PHONE)
        CommunicateRepository.placeCall(context, "+14155550102")
        val intent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_DIAL, intent.action)
        assertEquals("tel:%2B14155550102", intent.data.toString())
    }

    // -- JID / recipient builders --

    @Test
    fun `whatsApp JID builder passes through JIDs and normalizes numbers`() {
        with(CommunicateRepository) {
            assertEquals("123@g.us", toWhatsAppJid(context, "123@g.us"))
            assertEquals("14155550102@s.whatsapp.net", toWhatsAppJid(context, "+1 415-555-0102"))
            assertEquals("14155550102@s.whatsapp.net", toWhatsAppJid(context, "4155550102"))
            assertEquals("@s.whatsapp.net", toWhatsAppJid(context, "not-a-number"))
        }
    }

    @Test
    fun `signal recipient builder passes through qualified ids`() {
        with(CommunicateRepository) {
            assertEquals("", toSignalRecipient(context, " "))
            assertEquals("group:abc", toSignalRecipient(context, "group:abc"))
            assertEquals("123@s.whatsapp.net", toSignalRecipient(context, "123@s.whatsapp.net"))
            val uuid = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
            assertEquals(uuid, toSignalRecipient(context, uuid))
            assertEquals("+14155550102", toSignalRecipient(context, "4155550102"))
            assertEquals("+442079460958", toSignalRecipient(context, "+44 20 7946 0958"))
        }
    }

    @Test
    fun `rcs recipient builder passes through URIs`() {
        with(CommunicateRepository) {
            assertEquals("", toRcsRecipient(context, ""))
            assertEquals("sip:+1415@rcs", toRcsRecipient(context, "sip:+1415@rcs"))
            assertEquals("rcs-group:1", toRcsRecipient(context, "rcs-group:1"))
            assertEquals("+14155550102", toRcsRecipient(context, "4155550102"))
        }
    }

    @Test
    fun `participants CSV parses JSON arrays and legacy CSV`() {
        with(CommunicateRepository) {
            assertEquals(emptyList(), parseParticipantsCsv(null))
            assertEquals(emptyList(), parseParticipantsCsv("  "))
            assertEquals(listOf("Alice", "Bob"), parseParticipantsCsv("[\"Alice\",\"Bob\"]"))
            assertEquals(listOf("Alice", "Bob"), parseParticipantsCsv("Alice, Bob"))
            assertEquals(listOf("Alice"), parseParticipantsCsv("Alice"))
            assertEquals(emptyList(), parseParticipantsCsv(", ,"))
        }
    }

    // -- Google Voice model conversions --

    @Test
    fun `gv call type mapping covers every kind`() {
        for ((gv, expected) in listOf(
            GvCallType.Incoming to CommunicateCallType.Incoming,
            GvCallType.Outgoing to CommunicateCallType.Outgoing,
            GvCallType.Missed to CommunicateCallType.Missed,
            GvCallType.Voicemail to CommunicateCallType.Voicemail,
            GvCallType.Unknown to CommunicateCallType.Unknown,
        )) {
            assertEquals(
                expected,
                GvCall("c", "+1", null, gv, 0L, 0L).toCallLogEntry(context).type,
            )
        }
    }

    @Test
    fun `gv thread message call and voicemail conversions`() {
        val thread = GvThread(
            id = "t.1", phoneNumber = "+14155550102", displayName = null, snippet = "",
            timestampMillis = 100L, unreadCount = 2,
            messages = listOf(
                GvMessage(
                    id = "m1", threadId = "t.1", phoneNumber = "+14155550102", text = "",
                    timestampMillis = 90L, outgoing = false, read = false,
                    mediaUrls = listOf("https://x/img.png"),
                ),
            ),
        )
        val smsThread = thread.toSmsThread(context)
        assertEquals(CommunicateLine.GoogleVoice, smsThread.line)
        assertEquals("t.1", smsThread.remoteId)
        assertEquals("+14155550102", smsThread.address)
        assertEquals(2, smsThread.unreadCount)
        assertEquals(CommunicateRepository.stableThreadId("t.1"), smsThread.threadId)
        assertTrue(smsThread.snippet.isNotBlank()) // media placeholder from resources

        val smsMessage = thread.messages.single().toSmsMessage(smsThread.threadId, context)
        assertTrue(smsMessage.body.isNotBlank())
        assertEquals(
            listOf(CommunicateAttachment("https://x/img.png", "image/*")),
            smsMessage.attachments,
        )
        val plain = GvMessage(
            id = "m2", threadId = "t.1", phoneNumber = "+1", text = "hello",
            timestampMillis = 1L, outgoing = true, read = true,
        ).toSmsMessage(7L, context)
        assertEquals("hello", plain.body)
        assertTrue(plain.outgoing)

        val call = GvCall(
            id = "c1", phoneNumber = "+14155550103", displayName = "Bob",
            type = GvCallType.Missed, timestampMillis = 5L, durationSeconds = 0L,
        ).toCallLogEntry(context)
        assertEquals(CommunicateCallType.Missed, call.type)
        assertEquals("Bob", call.displayName)
        assertEquals(CommunicateLine.GoogleVoice, call.line)
        val vm = GvVoicemail(
            id = "v1", phoneNumber = "+14155550104", timestampMillis = 9L,
            durationSeconds = 12L, transcript = "call me", audioUrl = "https://x/a.mp3", read = false,
        ).toVoicemailEntry(mapOf("Cookie" to "s=1"))
        assertEquals("audio/mpeg", vm.mimeType)
        assertTrue(vm.hasAudio)
        assertEquals("call me", vm.transcript)
        assertFalse(vm.isRead)
        assertEquals(mapOf("Cookie" to "s=1"), vm.audioHeaders)
        val vmNoAudio = GvVoicemail("v2", "+1", 0L, 0L, null, null, true).toVoicemailEntry(emptyMap())
        assertFalse(vmNoAudio.hasAudio)
        assertEquals("", vmNoAudio.contentUri)
    }

    // -- SIM send --

    @Test
    fun `sendSimSms validates blanks`() {
        with(CommunicateRepository) {
            assertFalse(sendSimSms(context, -1, "", "body"))
            assertFalse(sendSimSms(context, -1, "+14155550102", " "))
        }
    }

    @Test
    fun `sendSimSms without permission opens the composer`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.SEND_SMS)
        assertTrue(with(CommunicateRepository) { sendSimSms(context, -1, "+14155550102", "hi") })
        val intent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
    }

    @Test
    fun `sendSimSms with permission sends and records the sent row`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.SEND_SMS)
        assertTrue(with(CommunicateRepository) { sendSimSms(context, -1, "+14155550102", "hello") })
        val sent = FakeTelephonyState.inserts.filter { it.first.toString().contains("sms/sent") }
        assertEquals(1, sent.size)
        assertEquals("hello", sent.single().second?.getAsString(Telephony.Sms.BODY))
        assertEquals("+14155550102", sent.single().second?.getAsString(Telephony.Sms.ADDRESS))
    }

    @Test
    fun `sendSimSms long body goes multipart and still records one sent row`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.SEND_SMS)
        val body = "x".repeat(120)
        assertTrue(with(CommunicateRepository) { sendSimSms(context, 3, "+14155550102", body) })
        val sent = FakeTelephonyState.inserts.filter { it.first.toString().contains("sms/sent") }
        assertEquals(1, sent.size)
        assertEquals(body, sent.single().second?.getAsString(Telephony.Sms.BODY))
        assertEquals(3, sent.single().second?.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))
    }

    @Test
    fun `sendSimMms rejects empty recipients and missing permission`() {
        with(CommunicateRepository) {
            assertFalse(sendSimMms(context, -1, emptyList(), "hi", emptyList()))
            shadowOf(context as Application).denyPermissions(Manifest.permission.SEND_SMS)
            assertFalse(sendSimMms(context, -1, listOf("+14155550102"), "hi", emptyList()))
        }
    }

    @Test
    fun `persistOutgoingMms writes the mms row text part and addresses`() {
        with(CommunicateRepository) {
            persistOutgoingMms(context, -1, listOf("+14155550102", "+14155550103"), "group hi")
        }
        val uris = FakeTelephonyState.inserts.map { it.first.toString() }
        assertTrue(uris.any { it == "content://mms" }, "mms row inserted: $uris")
        assertTrue(uris.any { it.contains("/part") }, "text part inserted: $uris")
        val addrInserts = FakeTelephonyState.inserts.filter { it.first.toString().contains("/addr") }
        assertEquals(2, addrInserts.size)
        val part = FakeTelephonyState.inserts.first { it.first.toString().contains("/part") }
        assertEquals("group hi", part.second?.getAsString("text"))
    }

    @Test
    fun `sendAttachments reads bytes and reports failures`() = runTest {
        Robolectric.setupContentProvider(FakeMmsProvider::class.java, "w1docs")
        FakeTelephonyState.reset()
        // The fake MMS provider's openFile serves "file-body" for any part uri.
        val good = CommunicateAttachment("content://mms/part/71", "text/plain", "a.txt")
        val seen = mutableListOf<String>()
        val ok = with(CommunicateRepository) {
            sendAttachments(context, listOf(good)) { bytes, mime, name ->
                seen += "${bytes.decodeToString()}|$mime|$name"
                true
            }
        }
        assertTrue(ok)
        assertEquals(listOf("file-body|text/plain|a.txt"), seen)
        with(CommunicateRepository) {
            assertFalse(sendAttachments(context, emptyList()) { _, _, _ -> true })
            assertFalse(
                sendAttachments(context, listOf(good)) { _, _, _ -> false },
            )
            assertFalse(
                sendAttachments(context, listOf(good)) { _, _, _ -> throw IllegalStateException("x") },
            )
            assertFalse(
                sendAttachments(
                    context,
                    listOf(CommunicateAttachment("content://missing/x", "text/plain")),
                ) { _, _, _ -> true },
            )
        }
    }

    // -- unified send dispatch --

    @Test
    fun `sendMessage sim one-to-one sends an SMS`() = runTest {
        shadowOf(context as Application).grantPermissions(Manifest.permission.SEND_SMS)
        val ok = CommunicateRepository.sendMessage(
            context, LineChoice.Sim(-1, "SIM"), "+14155550102", "hello",
        )
        assertTrue(ok)
        assertTrue(FakeTelephonyState.inserts.any { it.first.toString().contains("sms/sent") })
    }

    @Test
    fun `sendMessage google voice needs an activity`() = runTest {
        val ok = CommunicateRepository.sendMessage(
            context, LineChoice.GoogleVoice, "+14155550102", "hello",
        )
        assertFalse(ok) // application context cannot mint the WebView token
    }

    @Test
    fun `sendMessage whatsApp fails cleanly when not connected`() = runTest {
        val ok = CommunicateRepository.sendMessage(
            context, LineChoice.WhatsApp, "+14155550102", "hello",
        )
        assertFalse(ok)
    }

    @Test
    fun `sendMessage signal blank and group sends fail cleanly`() = runTest {
        assertFalse(
            CommunicateRepository.sendMessage(context, LineChoice.Signal, "+14155550102", " "),
        )
        assertFalse(
            CommunicateRepository.sendMessage(
                context, LineChoice.Signal, "group:unknown", "hello",
                threadRemoteId = "group:unknown",
            ),
        )
    }

    @Test
    fun `sendMessage rcs falls back to SMS composer when incapable`() = runTest {
        shadowOf(context as Application).denyPermissions(Manifest.permission.SEND_SMS)
        val ok = CommunicateRepository.sendMessage(
            context, LineChoice.Rcs, "+14155550102", "hello",
        )
        assertTrue(ok) // fallback opens the SMS composer
        val intent = shadowOf(context as Application).nextStartedActivity
        assertNotNull(intent)
        assertEquals(android.content.Intent.ACTION_SENDTO, intent.action)
    }

    @Test
    fun `poll and contact dispatch reject unsupported lines`() = runTest {
        with(CommunicateRepository) {
            assertFalse(sendPoll(context, CommunicateLine.Sim, "c1", "Q?", listOf("a", "b")))
            assertFalse(sendPoll(context, CommunicateLine.GoogleVoice, "c1", "Q?", listOf("a")))
            assertFalse(sendPoll(context, CommunicateLine.Rcs, "c1", "Q?", listOf("a")))
            assertFalse(
                sendPollVote(context, CommunicateLine.Sim, "c1", "m1", listOf("a")),
            )
            assertFalse(
                sendPollVote(context, CommunicateLine.GoogleVoice, "c1", "m1", listOf("a")),
            )
            val card = com.vayunmathur.communicate.ui.SharedContactCard(
                givenName = "Alice", familyName = "Lee",
                phoneNumbers = listOf("+14155550102" to "Mobile"), emails = listOf("a@x.org"),
            )
            assertFalse(shareContact(context, CommunicateLine.Sim, "c1", card))
            assertFalse(shareContact(context, CommunicateLine.GoogleVoice, "c1", card))
            assertFalse(shareContact(context, CommunicateLine.Rcs, "c1", card))
        }
    }

    // -- merged loads (SIM rows + signed-out network lines) --

    @Test
    fun `merged thread and message loads route by line`() = runTest {
        FakeTelephonyState.smsRows = listOf(
            mapOf(
                Telephony.Sms._ID to 1L, Telephony.Sms.THREAD_ID to 10L,
                Telephony.Sms.ADDRESS to "+14155550102", Telephony.Sms.BODY to "sim hi",
                Telephony.Sms.DATE to 2_000L, Telephony.Sms.TYPE to Telephony.Sms.MESSAGE_TYPE_INBOX,
                Telephony.Sms.READ to 0, Telephony.Sms.STATUS to Telephony.Sms.STATUS_NONE,
                Telephony.Sms.SUBSCRIPTION_ID to -1,
            ),
        )
        val threads = CommunicateRepository.loadSmsThreadsMerged(context)
        assertEquals(1, threads.size)
        assertEquals(CommunicateLine.Sim, threads.single().line)

        val simThread = threads.single()
        val simMessages = CommunicateRepository.loadSmsMessagesMerged(context, simThread)
        assertEquals(listOf("sim hi"), simMessages.map { it.body })

        // Network lines with no local rows resolve to empty, never throw.
        val waThread = SmsThread(
            threadId = 1L, address = "+14155550102", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0, line = CommunicateLine.WhatsApp,
        )
        assertTrue(CommunicateRepository.loadSmsMessagesMerged(context, waThread).isEmpty())
        assertTrue(
            CommunicateRepository.loadSmsMessagesMerged(
                context, waThread.copy(line = CommunicateLine.Signal),
            ).isEmpty(),
        )
        assertTrue(
            CommunicateRepository.loadSmsMessagesMerged(
                context, waThread.copy(line = CommunicateLine.Rcs, remoteId = "+14155550102"),
            ).isEmpty(),
        )
        assertTrue(
            CommunicateRepository.loadSmsMessagesMerged(
                context, waThread.copy(line = CommunicateLine.GoogleVoice),
            ).isEmpty(), // no remoteId
        )
        assertTrue(CommunicateRepository.loadCallLogsMerged(context).isEmpty())
        assertTrue(CommunicateRepository.loadVoicemailsMerged(context).isEmpty())
    }

    // -- delete routing --

    @Test
    fun `deleteConversation routes sim deletes and rejects missing remote ids`() = runTest {
        FakeTelephonyState.deleteResult = 1
        val sim = SmsThread(
            threadId = 10L, address = "+1", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0,
        )
        assertTrue(CommunicateRepository.deleteConversation(context, sim))
        assertTrue(FakeTelephonyState.deletes.any { it.toString().contains("mms-sms/conversations/10") })
        assertFalse(
            CommunicateRepository.deleteConversation(
                context, sim.copy(line = CommunicateLine.WhatsApp),
            ),
        )
        assertFalse(
            CommunicateRepository.deleteConversation(
                context, sim.copy(line = CommunicateLine.Signal),
            ),
        )
        assertFalse(
            CommunicateRepository.deleteConversation(
                context, sim.copy(line = CommunicateLine.Rcs),
            ),
        )
        assertFalse(
            CommunicateRepository.deleteConversation(
                context, sim.copy(line = CommunicateLine.GoogleVoice),
            ),
        )
    }

    @Test
    fun `deleteConversation sim falls back to sms and mms row deletes`() = runTest {
        FakeTelephonyState.deleteResult = 0
        val sim = SmsThread(
            threadId = 10L, address = "+1", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0,
        )
        assertTrue(CommunicateRepository.deleteConversation(context, sim))
        // conversation delete (0) then SMS + MMS fallback deletes
        assertTrue(FakeTelephonyState.deletes.size >= 3)
    }

    @Test
    fun `deleteCallLog routes by line`() = runTest {
        FakeTelephonyState.deleteResult = 1
        val entry = CommunicateCallLogEntry(
            id = 3L, displayName = null, phoneNumber = "+1",
            type = CommunicateCallType.Incoming, timestampMillis = 0L, durationSeconds = 0L,
        )
        assertTrue(CommunicateRepository.deleteCallLog(context, entry))
        assertTrue(
            CommunicateRepository.deleteCallLog(
                context, entry.copy(line = CommunicateLine.GoogleVoice),
            ),
        ) // GV call rows are local-only no-ops
        assertFalse(
            CommunicateRepository.deleteCallLog(
                context, entry.copy(line = CommunicateLine.WhatsApp),
            ),
        )
        assertFalse(
            CommunicateRepository.deleteCallLog(
                context, entry.copy(line = CommunicateLine.Rcs),
            ),
        )
    }

    @Test
    fun `voicemail merged routing rejects gv rows without remote id`() = runTest {
        val gvVm = VoicemailEntry(
            id = 1L, phoneNumber = "+1", timestampMillis = 0L, durationSeconds = 0L,
            contentUri = "", mimeType = null, hasAudio = false, transcript = null,
            isRead = false, line = CommunicateLine.GoogleVoice,
        )
        assertFalse(CommunicateRepository.markVoicemailEntryRead(context, gvVm))
        assertFalse(CommunicateRepository.deleteVoicemailEntry(context, gvVm))
    }
}
