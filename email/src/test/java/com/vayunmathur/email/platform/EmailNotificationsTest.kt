package com.vayunmathur.email.platform

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** [EmailNotifications]: empty input posts nothing; messages post per-account channel notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailNotificationsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun message(id: Long, dateMillis: Long) = EmailMessage(
        accountEmail = "me@x.com", folderName = "INBOX", id = id,
        subject = "Subject $id", from = "Sender <f@x.com>", date = "d",
        dateMillis = dateMillis, body = "Body $id", threadId = "t$id",
    )

    @Test
    fun `empty list posts nothing`() {
        EmailNotifications.postForNewMessages(context, "me@x.com", emptyList())
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(Shadows.shadowOf(nm).allNotifications.isEmpty())
    }

    @Test
    fun `messages post notifications on the per-account channel`() {
        EmailNotifications.ensureChannel(context, "me@x.com")
        EmailNotifications.postForNewMessages(
            context, "me@x.com",
            listOf(message(2L, 2000L), message(1L, 1000L)),
        )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = Shadows.shadowOf(nm).allNotifications
        // Two children plus the one per-account group summary.
        assertEquals(3, posted.size)
        assertTrue(posted.all { it.channelId == "new_mail::me@x.com" })
        val children = posted.filter {
            (it.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0
        }
        assertEquals(2, children.size)
        val titles = children.map { it.extras.getCharSequence("android.title")?.toString() }
        assertTrue(titles.all { it == "Sender" })
    }

    @Test
    fun `children are grouped per account with one InboxStyle summary`() {
        EmailNotifications.postForNewMessages(
            context, "me@x.com",
            listOf(message(2L, 2000L), message(1L, 1000L)),
        )
        // A second batch for the same account must not add a second summary.
        EmailNotifications.postForNewMessages(context, "me@x.com", listOf(message(3L, 3000L)))

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = nm.activeNotifications.toList()
        val summaries = active.filter {
            (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        }
        val children = active.filter {
            (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0
        }
        assertEquals(1, summaries.size)
        assertEquals(3, children.size)
        assertTrue(active.all { it.notification.group == "me@x.com" })
        assertTrue(children.none { it.id == summaries.single().id })

        val summary = summaries.single().notification
        assertEquals("new_mail::me@x.com", summary.channelId)
        val lines = summary.extras.getCharSequenceArray("android.textLines").orEmpty()
        assertEquals(3, lines.size)
        assertTrue(lines.any { it.toString().contains("Subject 3") })
        // Summary tap opens the app.
        val launch = Shadows.shadowOf(summary.contentIntent).savedIntent
        assertEquals(
            com.vayunmathur.email.MainActivity::class.java.name,
            launch.component?.className,
        )
    }

    @Test
    fun `cancelForMessages keeps child ids and drops summary only when group empties`() {
        val first = message(1L, 1000L)
        val second = message(2L, 2000L)
        EmailNotifications.postForNewMessages(context, "me@x.com", listOf(first, second))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(3, nm.activeNotifications.size)

        EmailNotifications.cancelForMessages(context, listOf(first))
        // One child + the summary remain.
        assertEquals(2, nm.activeNotifications.size)
        assertTrue(
            nm.activeNotifications.any {
                (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
            },
        )

        EmailNotifications.cancelForMessages(context, listOf(second))
        assertTrue(nm.activeNotifications.isEmpty())
    }
}
