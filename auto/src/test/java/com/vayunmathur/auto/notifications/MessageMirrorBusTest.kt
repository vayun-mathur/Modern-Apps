package com.vayunmathur.auto.notifications

import com.vayunmathur.auto.protocol.gal.MessagingMessage
import com.vayunmathur.auto.protocol.gal.MessagingThread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Auto module: [MessageMirrorBus] — the process-wide bus between the
 * notification listener and the projection session. Posts fan out to every
 * registered listener; closing the registration stops delivery.
 */
class MessageMirrorBusTest {

    private fun thread(id: String) = MessagingThread.newBuilder()
        .setThreadId(id)
        .setTitle("Title $id")
        .build()

    private fun message(threadId: String) = MessagingMessage.newBuilder()
        .setThreadId(threadId)
        .setMessageId("m-$threadId")
        .setBody("hello $threadId")
        .build()

    @Test
    fun `a post with no listeners is a silent no-op`() {
        MessageMirrorBus.post(thread("t0"), message("t0"), null)
    }

    @Test
    fun `a registered listener receives the thread message and route`() {
        var seenThread: MessagingThread? = null
        var seenMessage: MessagingMessage? = null
        var seenRoute: MessageReplyRoute? = null
        var calls = 0
        val route = MessageReplyRoute("t1", null, null, null)
        val registration = MessageMirrorBus.register { t, m, r ->
            seenThread = t
            seenMessage = m
            seenRoute = r
            calls++
        }
        try {
            MessageMirrorBus.post(thread("t1"), message("t1"), route)
            assertEquals(1, calls)
            assertEquals("t1", seenThread?.threadId)
            assertEquals("m-t1", seenMessage?.messageId)
            assertEquals(route, seenRoute)
        } finally {
            registration.close()
        }
    }

    @Test
    fun `closing the registration stops delivery`() {
        var calls = 0
        val registration = MessageMirrorBus.register { _, _, _ -> calls++ }
        registration.close()
        MessageMirrorBus.post(thread("t2"), message("t2"), null)
        assertEquals(0, calls)
    }

    @Test
    fun `multiple listeners each receive every post`() {
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val r1 = MessageMirrorBus.register { t, _, _ -> first += t.threadId }
        val r2 = MessageMirrorBus.register { t, _, _ -> second += t.threadId }
        try {
            MessageMirrorBus.post(thread("a"), message("a"), null)
            MessageMirrorBus.post(thread("b"), message("b"), null)
            assertEquals(listOf("a", "b"), first)
            assertEquals(listOf("a", "b"), second)
        } finally {
            r1.close()
            r2.close()
        }
    }

    @Test
    fun `a null route arrives as null at the listener`() {
        var routeSeen: MessageReplyRoute? = MessageReplyRoute("x", null, null, null)
        var calls = 0
        val registration = MessageMirrorBus.register { _, _, r ->
            routeSeen = r
            calls++
        }
        try {
            MessageMirrorBus.post(thread("t3"), message("t3"), null)
            assertEquals(1, calls)
            assertNull(routeSeen)
        } finally {
            registration.close()
        }
    }
}
