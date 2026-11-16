package com.vayunmathur.auto

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.notifications.MessageMirrorBus
import com.vayunmathur.auto.notifications.MessageReplyRoute
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.MessagingAction
import com.vayunmathur.auto.protocol.gal.MessagingActionType
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import com.vayunmathur.auto.service.MessagingCarAppService
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class Y3MessagingRouteTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(b: ByteArray, o: Int, l: Int): Int = -1
        override fun write(b: ByteArray, o: Int, l: Int) { written.write(b, o, l) }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private fun connection(): GalConnection {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), ssl, deviceModel = "Test").also { it.close() }
    }

    private fun action(type: MessagingActionType, threadId: String, text: String = ""): ByteArray =
        MessagingAction.newBuilder().setAction(type).setThreadId(threadId).setReplyText(text).build().toByteArray()

    @Test fun replyWithRouteSendsAndMarkReadFires() {
        val events = mutableListOf<MessagingEvent>()
        val replies = mutableListOf<Pair<String, String>>()
        val svc = MessagingCarAppService(
            connection(),
            onEvent = { events += it },
            onReply = { t, x -> replies += t to x },
            context = { ctx },
        )
        svc.onChannelOpen()
        val replyIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).setAction("reply"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val readIntent = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).setAction("read"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val route = MessageReplyRoute(
            threadId = "t7",
            replyIntent = replyIntent,
            remoteInput = RemoteInput.Builder("reply_key").build(),
            readIntent = readIntent,
        )
        // Posting the phone message registers the route for this thread.
        MessageMirrorBus.post(
            MessagingThread.newBuilder().setThreadId("t7").setTitle("Ada").build(),
            MessagingMessage.newBuilder().setThreadId("t7").setMessageId("m7").setSender("Ada").setBody("hi").setTimestampMs(1L).build(),
            route,
        )
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_REPLY, "t7", "on my way"))
        assertEquals(listOf("t7" to "on my way"), replies)
        assertTrue(events.any { it is MessagingEvent.ReplySent || it is MessagingEvent.ReplyFailed }, "$events")
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_MARK_READ, "t7"))
        assertTrue(events.any { it is MessagingEvent.MarkedRead })
        svc.release()
    }
}
