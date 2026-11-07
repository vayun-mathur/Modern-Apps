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
        assertEquals(2, posted.size)
        assertTrue(posted.all { it.channelId == "new_mail::me@x.com" })
        val titles = posted.map { it.extras.getCharSequence("android.title")?.toString() }
        assertTrue(titles.all { it == "Sender" })
    }
}
