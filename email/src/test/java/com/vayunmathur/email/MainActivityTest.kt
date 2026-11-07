package com.vayunmathur.email

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.email.data.EmailAccount
import com.vayunmathur.email.data.EmailDatabase
import com.vayunmathur.email.data.EmailRepository
import com.vayunmathur.email.platform.IntentState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * [MainActivity] intent routing: notification deep links, compose/share
 * intents and .eml VIEW intents must land in [IntentState] as the right
 * route before the nav graph reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase

    @BeforeTest
    fun open() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        db = Room.inMemoryDatabaseBuilder(context, EmailDatabase::class.java)
            .allowMainThreadQueries().build()
        resetRepositorySingleton()
        com.vayunmathur.library.util.databases[EmailDatabase::class] = db
        runBlocking { db.accountDao().insertAccount(EmailAccount(email = "me@x.com")) }
        IntentState.navigationRoute = null
    }

    @AfterTest
    fun close() {
        IntentState.navigationRoute = null
        com.vayunmathur.library.util.databases.remove(EmailDatabase::class)
        resetRepositorySingleton()
        db.close()
    }

    private fun resetRepositorySingleton() {
        val field = EmailRepository::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Test
    fun `notification deep link routes to the message thread`() {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("accountEmail", "me@x.com")
            putExtra("threadId", "t-42")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertEquals(Route.MessageThread("me@x.com", "t-42"), IntentState.navigationRoute)
        controller.destroy()
    }

    @Test
    fun `sendto share routes to the composer with the recipient`() {
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:you@y.com".toUri()).apply {
            setClass(context, MainActivity::class.java)
            putExtra(Intent.EXTRA_SUBJECT, "Hello")
            putExtra(Intent.EXTRA_TEXT, "Body text")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertEquals(
            Route.Composer(to = "you@y.com", subject = "Hello", body = "Body text"),
            IntentState.navigationRoute,
        )
        controller.destroy()
    }

    @Test
    fun `eml view intent routes to the eml viewer`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType("content://docs/message.eml".toUri(), "message/rfc822")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertEquals(Route.EmlViewer("content://docs/message.eml"), IntentState.navigationRoute)
        controller.destroy()
    }

    /** Serves a single display-name row for content://docs URIs. */
    class DocsProvider(private val displayName: String) : ContentProvider() {
        override fun onCreate(): Boolean = true
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor {
            val cols = projection ?: arrayOf("_display_name")
            return MatrixCursor(cols).apply { addRow(cols.map { displayName }) }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    }

    @Test
    fun `eml view intent detected from the provider display name`() {
        ShadowContentResolver.registerProviderInternal("docs", DocsProvider("message.eml"))
        val intent = Intent(Intent.ACTION_VIEW, "content://docs/42".toUri()).apply {
            setClass(context, MainActivity::class.java)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertEquals(Route.EmlViewer("content://docs/42"), IntentState.navigationRoute)
        controller.destroy()
    }

    @Test
    fun `non-eml display name does not route to the eml viewer`() {
        ShadowContentResolver.registerProviderInternal("docs", DocsProvider("notes.pdf"))
        val intent = Intent(Intent.ACTION_VIEW, "content://docs/43".toUri()).apply {
            setClass(context, MainActivity::class.java)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertNull(IntentState.navigationRoute)
        controller.destroy()
    }

    @Test
    fun `mbox typed view intent routes to the eml viewer`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setClass(context, MainActivity::class.java)
            setDataAndType("content://docs/archive".toUri(), "application/mbox")
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent)
        controller.create()
        assertEquals(Route.EmlViewer("content://docs/archive"), IntentState.navigationRoute)
        controller.destroy()
    }

    @Test
    fun `new intent re-routes while running`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java),
        )
        controller.create()
        controller.newIntent(
            Intent().apply {
                putExtra("accountEmail", "me@x.com")
                putExtra("threadId", "t-9")
            },
        )
        assertEquals(Route.MessageThread("me@x.com", "t-9"), IntentState.navigationRoute)
        controller.destroy()
    }
}
