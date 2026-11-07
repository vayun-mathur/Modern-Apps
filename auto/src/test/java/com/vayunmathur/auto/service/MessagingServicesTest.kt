package com.vayunmathur.auto.service

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.notifications.MessageMirrorBus
import com.vayunmathur.auto.platform.AudioEvent
import com.vayunmathur.auto.platform.MessagingEvent
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.GalService
import com.vayunmathur.auto.protocol.GalTransport
import com.vayunmathur.auto.protocol.gal.MessagingAction
import com.vayunmathur.auto.protocol.gal.MessagingActionType
import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import com.vayunmathur.auto.protocol.gal.Service
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MessagingServicesTest {
    private class FakeTransport : GalTransport {
        val written = ByteArrayOutputStream()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
        override fun write(bytes: ByteArray, offset: Int, length: Int) { written.write(bytes, offset, length) }
        override fun flush() = Unit
        override fun close() = Unit
    }
    private fun connection(): GalConnection {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        return GalConnection(FakeTransport(), ctx, deviceModel = "Test").also { it.close() }
    }
    private fun action(type: MessagingActionType, thread: String, text: String = ""): ByteArray =
        MessagingAction.newBuilder().setAction(type).setThreadId(thread).setReplyText(text).build().toByteArray()

    @Test fun `reply without route drops with failure and forwards to reply callback`() {
        val events = mutableListOf<MessagingEvent>()
        val replies = mutableListOf<Pair<String, String>>()
        val svc = MessagingCarAppService(connection(), onEvent = { events += it }, onReply = { t, x -> replies += t to x })
        assertEquals(GalService.NOTIFICATION.id, svc.channelId)
        svc.onMessage(999, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_REPLY, "t1", "hi"))
        assertTrue(events.isEmpty()) // wrong channel ignored
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_REPLY, "t1", "hi"))
        assertEquals(listOf("t1" to "hi"), replies)
        assertTrue(events.any { it is MessagingEvent.ReplyReceived && it.threadId == "t1" })
        assertTrue(events.any { it is MessagingEvent.ReplyFailed && it.reason == "no-route" })
        svc.release()
    }

    @Test fun `mark read without route and unhandled types are ignored`() {
        val events = mutableListOf<MessagingEvent>()
        val svc = MessagingCarAppService(connection(), onEvent = { events += it })
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_MARK_READ, "t9"))
        assertTrue(events.any { it is MessagingEvent.MarkedRead && it.threadId == "t9" })
        val before = events.size
        svc.onMessage(svc.channelId, 0x7777, byteArrayOf(1))
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, byteArrayOf(0))
        assertEquals(before, events.size)
        svc.release()
    }

    @Test fun `voice reply begin with no mic drops and phone messages post while open`() {
        val events = mutableListOf<MessagingEvent>()
        val svc = MessagingCarAppService(connection(), onEvent = { events += it })
        svc.onChannelOpen()
        svc.onMessage(svc.channelId, GalMessage.Notification.ACTION, action(MessagingActionType.MESSAGE_VOICE_REPLY_BEGIN, "t2"))
        // Mirror a phone message through the bus
        MessageMirrorBus.post(
            MessagingThread.newBuilder().setThreadId("t2").setTitle("Ada").setSender("+1").setLastMessageMs(1L).setUnreadCount(1).build(),
            MessagingMessage.newBuilder().setThreadId("t2").setMessageId("m1").setSender("Ada").setBody("hello").setTimestampMs(1L).build(),
            null,
        )
        assertTrue(events.any { it is MessagingEvent.ThreadsPosted })
        assertTrue(events.any { it is MessagingEvent.MessagePosted })
        svc.release()
        // After release, bus messages no longer post
        val before = events.size
        MessageMirrorBus.post(
            MessagingThread.newBuilder().setThreadId("t3").setTitle("Bob").setSender("+2").setLastMessageMs(2L).setUnreadCount(1).build(),
            MessagingMessage.newBuilder().setThreadId("t3").setMessageId("m2").setSender("Bob").setBody("late").setTimestampMs(2L).build(),
            null,
        )
        assertEquals(before, events.size)
    }

    @Test fun `car messaging audio drops tts without engine and voice without mic`() {
        val audioEvents = mutableListOf<AudioEvent>()
        val audio = CarMessagingAudio(tts = { null }, mic = { null }, onEvent = { audioEvents += it })
        audio.speakOnCar("hello")
        assertEquals(listOf("no-tts"), audioEvents.filterIsInstance<AudioEvent.TtsDropped>().map { it.reason })
        var result: String? = null
        audio.beginVoiceReply("t1") { result = it }
        assertEquals(null, result)
    }

    @Test fun `unowned service log handles every descriptor family`() {
        // Just exercise each branch; logging is the observable behaviour (no crash, no return value)
        for (id in listOf(
            GalService.BLUETOOTH.id, GalService.PHONE_STATUS.id, GalService.RADIO.id,
            GalService.VENDOR_EXTENSION.id, GalService.WIFI_PROJECTION.id, GalService.WIFI_DISCOVERY.id,
            GalService.CAR_CONTROL.id, GalService.CAR_LOCAL_MEDIA.id, GalService.BUFFERED_MEDIA_SINK.id,
            GalService.CAR_INTENT.id, GalService.VIDEO_SINK.id, 999,
        )) {
            observeUnownedService(Service.newBuilder().setId(id).build())
        }
        assertTrue(true) // reaching here without exception is the assertion for a log-only pass
    }
}
