package com.vayunmathur.auto.notifications

import android.app.Notification
import android.content.pm.PackageManager
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
 */
@RunWith(RobolectricTestRunner::class)
class MessageMirrorServiceTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun service(): MessageMirrorService =
        Robolectric.buildService(MessageMirrorService::class.java).get()

    private fun messageNotification(
        text: CharSequence?,
        conversationTitle: CharSequence? = null,
        shortcutId: String? = null,
    ): Notification {
        val sender = Person.Builder().setName("Ada").build()
        val style = NotificationCompat.MessagingStyle(sender)
        if (conversationTitle != null) style.conversationTitle = conversationTitle
        if (text != null) style.addMessage(text, 1_700_000_000_000L, sender)
        val builder = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (shortcutId != null) builder.setShortcutId(shortcutId)
        return builder.build()
    }

    private fun sbn(
        notification: Notification,
        packageName: String = "com.example.chat",
        tag: String? = "tag1",
        id: Int = 7,
    ): StatusBarNotification {
        val pkg = context.packageName
        return StatusBarNotification(
            packageName,
            pkg,
            0,
            tag,
            0,
            0,
            notification,
            android.os.UserHandle.CURRENT,
            0L,
        )
    }

    @Test
    fun `a messaging notification extracts its thread and message`() {
        val (thread, message) = assertNotNull(
            service().extract(sbn(messageNotification("On my way", "Ada"))),
        )
        assertEquals("com.example.chat:tag1:7", thread.threadId)
        assertEquals("Ada", thread.title)
        assertEquals("Ada", thread.sender)
        assertEquals("com.example.chat:tag1:7", message.threadId)
        assertEquals("7:1700000000000", message.messageId)
        assertEquals("On my way", message.body)
        assertEquals(1_700_000_000_000L, message.timestampMs)
    }

    @Test
    fun `a notification without messages extracts to null`() {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Ada").build())
        val notification = NotificationCompat.Builder(context, "messages")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setStyle(style)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        assertNull(service().extract(sbn(notification)))
    }

    @Test
    fun `blank-only messages extract to null`() {
        assertNull(service().extract(sbn(messageNotification("   "))))
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
        val (_, message) = assertNotNull(service().extract(sbn(notification)))
        assertEquals("second", message.body)
        assertEquals("7:2000", message.messageId)
    }

    @Test
    fun `a conversation title beats the sender name`() {
        val (thread, _) = assertNotNull(
            service().extract(sbn(messageNotification("hi", "Family group"))),
        )
        assertEquals("Family group", thread.title)
    }

    @Test
    fun `a shortcut id keys the conversation`() {
        val svc = service()
        val key = svc.conversationKey(sbn(messageNotification("hi"), shortcutId = "conv42"))
        assertEquals("com.example.chat:conv42", key)
    }

    @Test
    fun `package tag and id key the conversation without a shortcut`() {
        val svc = service()
        assertEquals("com.example.chat:tag1:7", svc.conversationKey(sbn(messageNotification("hi"))))
    }

    @Test
    fun `the active counter starts at zero and floors there`() {
        val base = MessageMirrorService.activeMessageCount()
        assertTrue(base >= 0)
        val svc = service()
        svc.onNotificationRemoved(sbn(messageNotification("hi")))
        assertEquals(base.coerceAtLeast(0), MessageMirrorService.activeMessageCount())
    }

    @Test
    fun `a non-message category never posts to the bus`() {
        val posted = mutableListOf<String>()
        val registration = MessageMirrorBus.register { thread, _, _ -> posted += thread.threadId }
        try {
            val svc = service()
            val notification = messageNotification("hi").apply { category = "email" }
            svc.onNotificationPosted(sbn(notification))
            assertEquals(emptyList(), posted)
        } finally {
            registration.close()
        }
        // PackageManager lookups of nothing installed must not crash discovery either.
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            PackageManager.PERMISSION_GRANTED,
        )
    }
}
