package com.vayunmathur.communicate.telephony

import android.content.Context
import android.util.Base64
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.e2e.RcsE2E
import com.vayunmathur.communicate.data.rcs.e2e.RcsPeerKeys
import com.vayunmathur.communicate.data.rcs.e2e.RustMlsCrypto
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: the inbound Welcome -> join -> decrypt path end to end through
 * [RcsSyncService.handleE2EEInbound] (the entry the task names as
 * RcsInboundParse.handleWelcome's caller), modelled as two devices, each
 * with its own in-memory RcsDatabase via [RcsDatabase.overrideForTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WelcomeJoinRcsPass3Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var deviceA: RcsDatabase
    private lateinit var deviceB: RcsDatabase

    private fun newDb(): RcsDatabase =
        Room.inMemoryDatabaseBuilder(context, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()

    private inline fun <T> onDevice(db: RcsDatabase, block: () -> T): T {
        RcsDatabase.overrideForTest = db
        try {
            return block()
        } finally {
            RcsDatabase.overrideForTest = deviceA
        }
    }

    private suspend fun <T> onDeviceSuspend(db: RcsDatabase, block: suspend () -> T): T {
        RcsDatabase.overrideForTest = db
        try {
            return block()
        } finally {
            RcsDatabase.overrideForTest = deviceA
        }
    }

    @BeforeTest fun setUp() {
        deviceA = newDb()
        deviceB = newDb()
        RcsDatabase.overrideForTest = deviceA
        RcsPeerKeys.clear()
    }

    @AfterTest fun tearDown() {
        RcsDatabase.overrideForTest = null
        deviceA.close()
        deviceB.close()
        RcsPeerKeys.clear()
    }

    private fun envelope(from: String, contentType: String, body: String, callId: String) =
        Envelope(from = from, contentType = contentType, raw = body, callId = callId, viaBranch = "br-p3")

    private fun wrapped(contentType: String, payload: ByteArray): String =
        "Content-Type: $contentType\r\n\r\n" + Base64.encodeToString(payload, Base64.NO_WRAP)

    @Test fun welcomeJoinThenEncryptedMessageEndToEnd() = runTest {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        try {
            if (!RustMlsCrypto.isAvailable) {
                // No host MLS library: the whole E2EE inbound route absorbs.
                val out = service.handleE2EEInbound(
                    envelope("+15550001002", RcsE2E.CT_WELCOME, wrapped(RcsE2E.CT_WELCOME, byteArrayOf(1, 2, 3)), "w0"),
                )
                assertNull(out)
                return@runTest
            }
            val meA = "+15550001001"
            val meB = "+15550001002"

            // Device B creates its identity and a key package to publish.
            val kpB = onDeviceSuspend(deviceB) {
                assertNotNull(RcsE2E.identityFor(context, meB))
                RcsE2E.freshKeyPackage(context, meB)
            }
            assertNotNull(kpB)

            // Device A adds B and gets the Welcome for B (A's thread is with B).
            val created = RcsE2E.createEncryptedGroup(context, meA, meB, listOf(kpB))
            assertNotNull(created)
            val (commit, welcome) = created

            // B receives the Welcome through the sync service inbound route.
            val notice = onDeviceSuspend(deviceB) {
                service.handleE2EEInbound(
                    envelope(meA, RcsE2E.CT_WELCOME, wrapped(RcsE2E.CT_WELCOME, welcome), "w1"),
                )
            }
            assertNotNull(notice)
            assertEquals(meA, notice.conversationId)
            assertEquals(meA, notice.senderId)
            assertEquals("🔒 Encrypted chat started", notice.body)
            assertEquals(InboundKind.Text, notice.kind)
            assertTrue(notice.messageId.startsWith("in-welcome-"))

            // The joined group is persisted on B and matches A's group id.
            val groupOnB = onDeviceSuspend(deviceB) { RcsE2E.groupIdFor(context, meA) }
            val groupOnA = RcsE2E.groupIdFor(context, meB)
            assertNotNull(groupOnB)
            assertEquals(groupOnA?.toList(), groupOnB.toList())

            // A encrypts; B receives the application message through the same
            // inbound route and sees the plaintext as an inbox row.
            val payload = RcsE2E.encryptTo(context, meA, meB, "hello over rcs".toByteArray(Charsets.UTF_8))
            assertNotNull(payload)
            val row = onDeviceSuspend(deviceB) {
                service.handleE2EEInbound(
                    envelope(meA, RcsE2E.CT_MLS, wrapped(RcsE2E.CT_MLS, payload), "m1"),
                )
            }
            assertNotNull(row)
            assertEquals("hello over rcs", row.body)
            assertEquals(meA, row.conversationId)
            assertTrue(row.messageId.startsWith("in-mls-"))

            // The add-commit delivered as data is absorbed without a row.
            val commitRow = onDeviceSuspend(deviceB) {
                service.handleE2EEInbound(
                    envelope(meA, RcsE2E.CT_COMMIT, wrapped(RcsE2E.CT_COMMIT, commit), "m2"),
                )
            }
            assertNull(commitRow)

            // B replies in the group; A decrypts through its inbound route.
            val reply = onDeviceSuspend(deviceB) {
                RcsE2E.encryptTo(context, meB, meA, "reply hello".toByteArray(Charsets.UTF_8))
            }
            assertNotNull(reply)
            val replyRow = service.handleE2EEInbound(
                envelope(meB, RcsE2E.CT_MLS, wrapped(RcsE2E.CT_MLS, reply), "m3"),
            )
            assertNotNull(replyRow)
            assertEquals("reply hello", replyRow.body)
        } finally {
            service.onDestroy()
        }
    }

    @Test fun welcomeNegativesAreAbsorbed() = runTest {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        try {
            // Undecodable body.
            assertNull(
                service.handleE2EEInbound(
                    envelope("+15550001002", RcsE2E.CT_WELCOME, "Content-Type: ${RcsE2E.CT_WELCOME}\r\n\r\n!!!not-base64!!!", "w-bad"),
                ),
            )
            // Decodable but not a Welcome.
            assertNull(
                service.handleE2EEInbound(
                    envelope("+15550001002", RcsE2E.CT_WELCOME, wrapped(RcsE2E.CT_WELCOME, byteArrayOf(9, 8, 7, 6, 5)), "w-junk"),
                ),
            )
            // Plain text is not E2EE at all.
            assertNull(
                service.handleE2EEInbound(
                    envelope("+15550001002", "text/plain", "just a hello", "t1"),
                ),
            )
            // MLS data for a conversation with no group cannot decrypt.
            assertNull(
                service.handleE2EEInbound(
                    envelope(
                        "+15550009999", RcsE2E.CT_MLS,
                        wrapped(RcsE2E.CT_MLS, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)), "m-x",
                    ),
                ),
            )
        } finally {
            service.onDestroy()
        }
    }

    @Test fun keyPackageAndKeyRequestInboundKinds() = runTest {
        val service = Robolectric.setupService(RcsSyncService::class.java)
        try {
            if (!RustMlsCrypto.isAvailable) return@runTest
            val meB = "+15550001002"
            val kpB = onDeviceSuspend(deviceB) {
                RcsE2E.identityFor(context, meB)
                RcsE2E.freshKeyPackage(context, meB)
            }
            assertNotNull(kpB)
            // B's publication arrives at A: cached, answered with a Typing-kind
            // control result, and no group auto-forms (no local number here).
            val kpBody = "Content-Type: ${RcsE2E.CT_KEY_PACKAGE}\r\n\r\n" +
                java.util.Base64.getEncoder().encodeToString(kpB)
            val kpResult = service.handleE2EEInbound(
                envelope(meB, RcsE2E.CT_KEY_PACKAGE, kpBody, "kp1"),
            )
            assertNotNull(kpResult)
            assertEquals(InboundKind.Typing, kpResult.kind)
            assertEquals(kpB.toList(), RcsPeerKeys.get(meB)?.toList())

            // A key request is absorbed the same way.
            val reqResult = service.handleE2EEInbound(
                envelope(meB, RcsE2E.CT_KEY_REQUEST, "Content-Type: ${RcsE2E.CT_KEY_REQUEST}\r\n\r\nrequest", "kr1"),
            )
            assertNotNull(reqResult)
            assertEquals(InboundKind.Typing, reqResult.kind)
            assertTrue(reqResult.messageId.startsWith("in-kreq-"))
        } finally {
            service.onDestroy()
        }
    }

    @Test fun encryptedInboundThroughServiceCallbackIsCached() = runTest {
        if (!RustMlsCrypto.isAvailable) return@runTest
        val meA = "+15550001001"
        val meB = "+15550001002"
        val kpB = onDeviceSuspend(deviceB) {
            RcsE2E.identityFor(context, meB)
            RcsE2E.freshKeyPackage(context, meB)
        }
        assertNotNull(kpB)
        val created = RcsE2E.createEncryptedGroup(context, meA, meB, listOf(kpB))
        assertNotNull(created)
        val welcome = created.second
        val service = Robolectric.setupService(RcsSyncService::class.java)
        try {
            // B joins the way production does, then starts its sync service.
            val notice = onDeviceSuspend(deviceB) {
                service.handleE2EEInbound(
                    envelope(meA, RcsE2E.CT_WELCOME, wrapped(RcsE2E.CT_WELCOME, welcome), "w-cb"),
                )
            }
            assertNotNull(notice)
            assertEquals(
                android.app.Service.START_STICKY,
                service.onStartCommand(null, 0, 5),
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (
                com.vayunmathur.communicate.data.rcs.RcsSipTransport.onInboundMessage == null &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20)
            }
            val callback = com.vayunmathur.communicate.data.rcs.RcsSipTransport.onInboundMessage
            assertNotNull(callback)
            // A sends an application message; on B it decrypts and caches.
            val payload = RcsE2E.encryptTo(context, meA, meB, "cached secret".toByteArray(Charsets.UTF_8))
            assertNotNull(payload)
            val headers = buildString {
                append("Via: SIP/2.0/TCP 10.0.0.1;branch=z9hG4bKcb\r\n")
                append("From: <sip:$meA@rcs>;tag=t1\r\n")
                append("To: <sip:$meB@rcs>\r\n")
                append("Call-ID: cb-mls@host\r\n")
                append("CSeq: 1 MESSAGE\r\n")
                append("Content-Type: ${RcsE2E.CT_MLS}\r\n")
            }
            val raw = wrapped(RcsE2E.CT_MLS, payload)
            // Keep B's database installed while the service scope handles
            // the message asynchronously (the callback only enqueues it).
            RcsDatabase.overrideForTest = deviceB
            callback(
                android.telephony.ims.SipMessage(
                    "MESSAGE sip:$meB@rcs SIP/2.0", headers, raw.toByteArray(Charsets.UTF_8),
                ),
            )
            // The service scope caches asynchronously into B's database.
            val dbDeadline = System.currentTimeMillis() + 15_000
            var bodies = emptyList<String>()
            while (System.currentTimeMillis() < dbDeadline) {
                bodies = deviceB.cachedMessageDao().getForConversation(meA).map { it.body }
                if ("cached secret" in bodies) break
                Thread.sleep(25)
            }
            assertTrue("cached secret" in bodies, "cached bodies: $bodies")
        } finally {
            RcsDatabase.overrideForTest = deviceA
            service.onDestroy()
        }
    }
}
