package com.vayunmathur.communicate.telephony

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.ImdnDisposition
import com.vayunmathur.communicate.data.rcs.RcsDatabase
import com.vayunmathur.communicate.data.rcs.RcsImdn
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSession
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import com.vayunmathur.communicate.data.rcs.buildIsComposingBody
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pass 3: RcsSyncService helpers — notification states, companion
 * start/stop intents, maintenance-loop first emissions, MSRP chunk
 * routing (IMDN / composing / plain text into the cache), shutdown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncServiceRcsPass3Test {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var testDb: RcsDatabase

    @BeforeTest fun setUp() {
        testDb = Room.inMemoryDatabaseBuilder(app, RcsDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        RcsDatabase.overrideForTest = testDb
    }

    @AfterTest fun tearDown() {
        RcsDatabase.overrideForTest = null
        testDb.close()
    }

    private fun service() = Robolectric.setupService(RcsSyncService::class.java)

    /** Polls [condition] (async service work runs on real IO threads). */
    private fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(15)
        assertTrue(condition(), "condition not met within ${timeoutMs}ms")
    }

    @Test fun companionStartAndStopIssueForegroundIntents() {
        RcsSyncService.start(app)
        val started = Shadows.shadowOf(app).nextStartedService
        assertNotNull(started)
        assertEquals(RcsSyncService::class.java.name, started.component?.className)
        assertNull(started.action)
        RcsSyncService.stop(app)
        val stopped = Shadows.shadowOf(app).nextStartedService
        assertNotNull(stopped)
        assertEquals("com.vayunmathur.communicate.rcs.STOP_SYNC", stopped.action)
    }

    @Test fun updateSyncNotificationForEachState() {
        val service = service()
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        service.updateSyncNotification(RcsRegistrationState.Available)
        assertNotNull(nm.getNotification(4723))
        service.updateSyncNotification(RcsRegistrationState.Unavailable(RcsUnavailableReason.NoSubscription))
        assertNotNull(nm.getNotification(4723))
        service.updateSyncNotification(RcsRegistrationState.Provisioning)
        service.updateSyncNotification(RcsRegistrationState.Unknown)
        service.updateSyncNotification(RcsRegistrationState.Disabled)
        assertNotNull(nm.getNotification(4723))
        service.onDestroy()
    }

    @Test fun startCommandNonStopActionGoesSticky() {
        val service = service()
        val result = service.onStartCommand(Intent("com.example.OTHER"), 0, 11)
        assertEquals(android.app.Service.START_STICKY, result)
        service.onDestroy()
    }

    @Test fun maintenanceLoopsFirstEmissionsAndShutdown() {
        val service = service()
        service.startMaintenanceLoops()
        // Teardown watch collects the transport state immediately and posts
        // the sync notification; the lifecycle loop sweeps empty sessions.
        val nm = Shadows.shadowOf(app.getSystemService(NotificationManager::class.java))
        awaitTrue { nm.getNotification(4723) != null }
        service.startMsrpLifecycle()
        service.startOutboxPump()
        service.shutdown()
        // Shutdown stops the service itself.
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        service.onDestroy()
    }

    @Test fun msrpChunkImdnUpdatesReceiptTracker() = runBlocking {
        val service = service()
        val session = RcsSession(
            dialogId = "d1", callId = "call-p3", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550003001@rcs", conversationId = "+15550003001",
        )
        val imdn = """
            <?xml version="1.0"?>
            <imdn xmlns="urn:ietf:params:xml:ns:imdn">
              <message-id>msg-p3-1</message-id>
              <status><positive-delivery/></status>
            </imdn>
        """.trimIndent()
        service.handleMsrpChunk("+15550003001", session, "message/imdn+xml", imdn.toByteArray())
        assertEquals(ImdnDisposition.Delivered, RcsImdn.statusFor("msg-p3-1"))
        service.onDestroy()
    }

    @Test fun msrpChunkComposingUpdatesTypingState() = runBlocking {
        val service = service()
        val session = RcsSession(
            dialogId = "d2", callId = "call-p3-2", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550003002@rcs", conversationId = "+15550003002",
        )
        service.handleMsrpChunk(
            "+15550003002", session, "application/im-iscomposing+xml",
            buildIsComposingBody(true).toByteArray(),
        )
        assertEquals(
            true,
            com.vayunmathur.communicate.data.rcs.RcsSessionManager.typing.value["+15550003002"],
        )
        service.onDestroy()
    }

    @Test fun msrpChunkPlainAndCpimTextLandsInCache() = runBlocking {
        val service = service()
        val session = RcsSession(
            dialogId = "d3", callId = "call-p3-3", localTag = "lt", remoteTag = "rt",
            remoteUri = "sip:+15550003003@rcs", conversationId = "+15550003003",
        )
        service.handleMsrpChunk("+15550003003", session, "text/plain", "plain hello".toByteArray())
        var rows = testDb.cachedMessageDao().getForConversation("+15550003003")
        assertEquals(1, rows.size)
        assertEquals("plain hello", rows[0].body)
        assertEquals(false, rows[0].outgoing)

        // CPIM-wrapped text unwraps before caching; blank bodies are dropped.
        val cpim = "Content-Type: message/cpim\r\n\r\nFrom: <sip:+15550003003@x>\r\nContent-Type: text/plain\r\n\r\nwrapped hello"
        service.handleMsrpChunk("+15550003003", session, "message/cpim", cpim.toByteArray())
        rows = testDb.cachedMessageDao().getForConversation("+15550003003")
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.body == "wrapped hello" })
        service.handleMsrpChunk("+15550003003", session, "text/plain", "   ".toByteArray())
        assertEquals(2, testDb.cachedMessageDao().getForConversation("+15550003003").size)
        // Conversation unread count accumulated.
        assertEquals(2, testDb.conversationDao().getConversation("+15550003003")?.unreadCount)
        service.onDestroy()
    }

    @Test fun relayWithoutHostedFocusIsNoOp() = runBlocking {
        val service = service()
        // No hosted focus for this conversation: returns quietly.
        service.relayFocusMessage(
            InboundRcs(conversationId = "+1999", body = "x", senderId = "+1999", messageId = "m1"),
            rawBody = "raw",
        )
        service.onDestroy()
    }
}
