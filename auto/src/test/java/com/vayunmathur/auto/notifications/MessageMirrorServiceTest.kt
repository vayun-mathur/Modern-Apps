package com.vayunmathur.auto.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Auto module: [MessageMirrorService] extraction. Only `MessagingStyle`
 * notifications with non-blank text extract to a ch14 snapshot; the
 * conversation key prefers the shortcut id and falls back to package+tag+id;
 * the active-message counter floors at zero.
 *
 * StatusBarNotifications come from posting through NotificationManager, the
 * same path the system listener would deliver them on.
 */
@RunWith(RobolectricTestRunner::class)
class MessageMirrorServiceTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun service(): MessageMirrorService =
        Robolectric.buildService(MessageMirrorService::class.java).get()

    private fun messageBuilder(
        text: CharSequence?,
        conversationTitle: CharSequence? = null,
        shortcutId: String? = null,
    ): NotificationCompat.Builder {
        val sender = Person.Builder().setName("Ada").build()
        val style = NotificationCompat.MessagingStyle(sender)
        if (conversationTitle != null) style.conversationTitle = conversationTitle
        if (text != null) style.addMessage(text, 1_700_000_000_000L, sender)
        return NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .apply { if (shortcutId != null) setShortcutId(shortcutId) }
    }

    private fun posted(tag: String, id: Int, notification: Notification): StatusBarNotification {
        manager.createNotificationChannel(
            NotificationChannel("messages", "messages", NotificationManager.IMPORTANCE_LOW),
        )
        manager.notify(tag, id, notification)
        return manager.activeNotifications.first { it.tag == tag && it.id == id }
    }

    private fun postedMessage(
        text: CharSequence?,
        conversationTitle: CharSequence? = null,
        shortcutId: String? = null,
        tag: String = "tag1",
        id: Int = 7,
    ): StatusBarNotification =
        posted(tag, id, messageBuilder(text, conversationTitle, shortcutId).build())

    @Test
    fun `a messaging notification extracts its thread and message`() {
        val (thread, message) = assertNotNull(
            service().extract(postedMessage("On my way", "Ada")),
        )
        assertEquals("${context.packageName}:tag1:7", thread.threadId)
        assertEquals("Ada", thread.title)
        assertEquals("Ada", thread.sender)
        assertEquals("${context.packageName}:tag1:7", message.threadId)
        assertEquals("7:1700000000000", message.messageId)
        assertEquals("On my way", message.body)
        assertEquals(1_700_000_000_000L, message.timestampMs)
    }

    @Test
    fun `a notification without messages extracts to null`() {
        val sender = Person.Builder().setName("Ada").build()
        val notification = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(NotificationCompat.MessagingStyle(sender))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        assertNull(service().extract(posted("tag2", 8, notification)))
    }

    @Test
    fun `blank-only messages extract to null`() {
        assertNull(service().extract(postedMessage("   ", tag = "tag3", id = 9)))
    }

    @Test
    fun `the latest non-blank message wins`() {
        val sender = Person.Builder().setName("Ada").build()
        val style = NotificationCompat.MessagingStyle(sender)
        style.addMessage("first", 1_000L, sender)
        style.addMessage("second", 2_000L, sender)
        val notification = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        val (_, message) = assertNotNull(service().extract(posted("tag4", 10, notification)))
        assertEquals("second", message.body)
        assertEquals("10:2000", message.messageId)
    }

    @Test
    fun `a conversation title beats the sender name`() {
        val (thread, _) = assertNotNull(
            service().extract(postedMessage("hi", "Family group", tag = "tag5", id = 11)),
        )
        assertEquals("Family group", thread.title)
    }

    @Test
    fun `a shortcut id keys the conversation`() {
        val svc = service()
        val key = svc.conversationKey(postedMessage("hi", shortcutId = "conv42", tag = "tag6", id = 12))
        assertEquals("${context.packageName}:conv42", key)
    }

    @Test
    fun `package tag and id key the conversation without a shortcut`() {
        val svc = service()
        assertEquals(
            "${context.packageName}:tag1:7",
            svc.conversationKey(postedMessage("hi")),
        )
    }

    @Test
    fun `the active counter starts at zero and floors there`() {
        val base = MessageMirrorService.activeMessageCount()
        assertTrue(base >= 0)
        service().onNotificationRemoved(postedMessage("hi", tag = "tag7", id = 13))
        assertEquals(0, MessageMirrorService.activeMessageCount())
    }

    @Test
    fun `a non-message category never posts to the bus`() {
        val posted = mutableListOf<String>()
        val registration = MessageMirrorBus.register { thread, _, _ -> posted += thread.threadId }
        try {
            val notification = messageBuilder("hi").build().apply { category = "email" }
            service().onNotificationPosted(posted("tag8", 14, notification))
            assertEquals(emptyList(), posted)
        } finally {
            registration.close()
        }
    }
}
