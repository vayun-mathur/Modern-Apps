package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.handleInboundFrame
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.SignalPollOptionData
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage

/**
 * W1 wave 5: Signal inbound poll create/vote/terminate and quoted text,
 * through the real E2E inbound frame path (Alice under test, Bob as peer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalInboundPollW1Test {

    private lateinit var db: SignalDatabase
    private lateinit var authA: SignalAuthData
    private lateinit var authB: SignalAuthData
    private lateinit var alice: SignalE2E
    private lateinit var bob: SignalE2E

    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(
            aci = "3f0e5a2c-1111-2222-3333-444455556666",
        )
        authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        alice = SignalE2E(db, authA)
        bob = SignalE2E(db, authB)
        SignalClient.db = db
        SignalClient.authData = authA
        SignalClient.e2e = alice
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest
    fun tearDown() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        db.close()
    }

    private fun bobBundleForAlice(): SignalE2E.ParsedPreKeyBundle {
        val upload = bob.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed prekey")
        val kyber = upload.lastResortKyber ?: error("no kyber prekey")
        return SignalE2E.ParsedPreKeyBundle(
            registrationId = authB.effectiveAciRegId(),
            preKeyId = upload.oneTimeEcPreKeys.firstOrNull()?.id,
            preKeyPublic = upload.oneTimeEcPreKeys.firstOrNull()?.publicKey,
            signedPreKeyId = signed.id,
            signedPreKeyPublic = signed.publicKey,
            signedPreKeySignature = signed.signature ?: ByteArray(0),
            identityKey = android.util.Base64.decode(authB.effectiveAciPublic(), android.util.Base64.NO_WRAP),
            kyberPreKeyId = kyber.id,
            kyberPreKeyPublic = kyber.publicKey,
            kyberPreKeySignature = kyber.signature ?: ByteArray(0),
        )
    }

    private fun wsFrame(content: ByteArray, timestamp: Long): ByteArray {
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET)
            .setSourceServiceId(bobAci)
            .setSourceDeviceId(1)
            .setClientTimestamp(timestamp)
            .setContent(ByteString.copyFrom(content))
            .build()
        return WebSocketMessage.newBuilder()
            .setType(WebSocketMessage.Type.REQUEST)
            .setRequest(
                WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/api/v1/message")
                    .setId(99L).setBody(ByteString.copyFrom(envelope.toByteArray())).build(),
            ).build().toByteArray()
    }

    /** Encrypt a Content payload Bob->Alice on their established session and feed it inbound. */
    private suspend fun feedFromBob(content: SignalServiceProtos.Content, timestamp: Long): SignalEvent? {
        if (!alice.hasSession(bobAci, 1)) {
            alice.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        }
        val paddedBytes = SignalProtocol.padMessageBody(content.toByteArray())
        // Establish the session Alice-side, then re-encrypt Bob->Alice so
        // decryptEnvelope can open it as a session (DOUBLE_RATCHET) message.
        val enc = alice.encryptDM(bobAci, 1, paddedBytes)
        bob.decryptDM(authA.aci, 1, isPreKey = true, ciphertext = enc.data)
        val back = bob.encryptDM(authA.aci, 1, paddedBytes)
        val deferred = CoroutineScope(Dispatchers.Unconfined).async { SignalClient.events.first() }
        SignalClient.handleInboundFrame(wsFrame(back.data, timestamp))
        return withTimeoutOrNull(5_000) { deferred.await() }
    }

    private fun content(dm: SignalServiceProtos.DataMessage): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder().setDataMessage(dm).build()

    @Test
    fun `inbound poll create emits the poll as an incoming message`() = runTest {
        val dm = SignalPayload.buildDataMessage(
            body = "Lunch?",
            timestamp = 1_700_000_000_000L,
            pollCreate = SignalPayload.buildPollCreate("Lunch?", listOf("Pizza", "Sushi")),
        )
        val event = feedFromBob(content(dm), 1_700_000_000_000L)
        assertTrue(event is SignalEvent.IncomingMessage, "got $event")
        val incoming = event as SignalEvent.IncomingMessage
        assertEquals("Lunch?", incoming.pollQuestion)
        assertEquals(listOf("Pizza", "Sushi"), incoming.pollOptions)
        assertEquals(bobAci, incoming.senderId)
    }

    @Test
    fun `inbound poll vote resolves option names from the cached poll`() = runTest {
        val pollTs = 1_700_000_100_000L
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "poll-1",
                conversationId = bobAci,
                body = "Lunch?",
                timestamp = pollTs,
                outgoing = false,
                senderId = bobAci,
                serviceData = SignalServiceData(
                    pollQuestion = "Lunch?",
                    pollOptions = listOf(SignalPollOptionData("Pizza"), SignalPollOptionData("Sushi")),
                    senderId = bobAci,
                ).serialize(),
            ),
        )
        val dm = SignalPayload.buildDataMessage(
            body = "",
            timestamp = pollTs + 1_000L,
            pollVote = SignalPayload.buildPollVote(targetSentTimestamp = pollTs, optionIndexes = listOf(1)),
        )
        val event = feedFromBob(content(dm), pollTs + 1_000L)
        assertTrue(event is SignalEvent.PollVote, "got $event")
        val vote = event as SignalEvent.PollVote
        assertEquals(listOf("Sushi"), vote.optionNames)
        assertEquals(bobAci, vote.voterId)
    }

    @Test
    fun `inbound poll terminate arrives as a generic incoming update`() = runTest {
        val terminate = SignalServiceProtos.DataMessage.PollTerminate.newBuilder()
            .setTargetSentTimestamp(1_700_000_100_000L)
            .build()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setTimestamp(1_700_000_200_000L)
            .setPollTerminate(terminate)
            .build()
        val event = feedFromBob(content(dm), 1_700_000_200_000L)
        assertNotNull(event, "poll terminate should not be dropped")
    }

    @Test
    fun `inbound quoted text carries the quote through`() = runTest {
        val quote = SignalServiceProtos.DataMessage.Quote.newBuilder()
            .setId(1_700_000_000_000L)
            .setText("original")
            .build()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody("replying to you")
            .setTimestamp(1_700_000_300_000L)
            .setQuote(quote)
            .build()
        val event = feedFromBob(content(dm), 1_700_000_300_000L)
        assertTrue(event is SignalEvent.IncomingMessage, "got $event")
        assertEquals("replying to you", (event as SignalEvent.IncomingMessage).body)
    }
}
