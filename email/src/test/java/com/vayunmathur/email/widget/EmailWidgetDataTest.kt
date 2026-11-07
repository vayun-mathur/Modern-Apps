package com.vayunmathur.email.widget

import android.app.Application
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.data.EmailPreview
import com.vayunmathur.email.data.EmailRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [EmailWidget] data layer and Glance layout: preview data is the fixed
 * sample inbox shown in the widget picker, [EmailWidget.loadData] reads
 * the unified-inbox previews from the repository database (newest first,
 * snippets computed on insert, other folders excluded), and the composed
 * layout shows subject/sender/snippet rows or the empty state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmailWidgetDataTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase
    private val widget = EmailWidget()

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
    }

    @AfterTest
    fun close() {
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun seedInbox() {
        runBlocking {
            db.messageDao().insertMessages(
                listOf(
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "INBOX", id = 1,
                        subject = "older subject", from = "Ann <a@b.com>", date = "d", dateMillis = 100,
                        body = "older body text",
                    ),
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "INBOX", id = 2,
                        subject = "newer subject", from = "Cat <c@d.com>", date = "d", dateMillis = 200,
                        body = "newer body text",
                    ),
                    EmailMessage(
                        accountEmail = "me@x.com", folderName = "Sent", id = 3,
                        subject = "sent subject", from = "me@x.com", date = "d", dateMillis = 300,
                        body = "sent body",
                    ),
                )
            )
        }
    }

    @Test
    fun `preview data is the three sample messages`() {
        val preview = widget.previewData(context)
        assertEquals(3, preview.size)
        assertEquals(
            listOf("Lunch tomorrow?", "Q3 report is ready", "Your weekly digest"),
            preview.map { it.subject },
        )
        assertEquals(listOf(1L, 2L, 3L), preview.map { it.id })
        assertTrue(preview.all { it.folderName == "INBOX" })
        assertTrue(preview.all { it.peekContent.isNotBlank() })
    }

    @Test
    fun `loadData returns unified inbox previews newest first`() {
        seedInbox()
        val loaded = runBlocking { widget.loadData(context, AppWidgetId(7)) }
        assertEquals(listOf("newer subject", "older subject"), loaded.map { it.subject })
        assertTrue(loaded.first().peekContent.contains("newer body"))
        assertEquals("Cat <c@d.com>", loaded.first().from)
    }

    @Test
    fun `loadData is empty when the inbox is empty`() {
        val loaded = runBlocking { widget.loadData(context, AppWidgetId(7)) }
        assertEquals(emptyList(), loaded)
    }

    @Test
    fun `content composes subject sender and snippet rows`() {
        val data = listOf(
            EmailPreview(
                accountEmail = "me@x.com", folderName = "INBOX", id = 1,
                subject = "composed subject", from = "Dana <d@e.com>", date = "Jul 6",
                peekContent = "composed snippet",
            )
        )
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(400.dp, 300.dp))
            provideComposable { widget.Content(data) }
            awaitIdle()
            onNode(hasText("Unified Inbox")).assertExists()
            onNode(hasText("composed subject")).assertExists()
            onNode(hasText("Dana")).assertExists()
            onNode(hasText("composed snippet")).assertExists()
        }
    }

    @Test
    fun `content composes the empty state without messages`() {
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(400.dp, 300.dp))
            provideComposable { widget.Content(emptyList()) }
            awaitIdle()
            onNode(hasText("No recent emails")).assertExists()
        }
    }

    @Test
    fun `provideGlance and providePreview run through the real entry points`() {
        seedInbox()
        // Without a widget host these cannot deliver RemoteViews, but the
        // loadData -> Content path (and the preview path) must run and the
        // MaWidget shell must swallow any host failure instead of throwing.
        runBlocking { widget.provideGlance(context, AppWidgetId(11)) }
        runBlocking { widget.providePreview(context, 0) }
    }
}
