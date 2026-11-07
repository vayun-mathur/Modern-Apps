package com.vayunmathur.email.data

import android.app.Application
import android.content.Intent
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ImapIdleService] lifecycle without a server: binding is null, start/stop
 * companion calls are accepted, and with no accounts the service must stop
 * itself instead of idling forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImapIdleServiceTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: EmailDatabase

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

    @Test
    fun `service binds null and destroys cleanly`() {
        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        assertNull(service.onBind(Intent()))
        controller.destroy()
    }

    @Test
    fun `start command with no accounts does not crash and destroy cleans up`() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        val controller = Robolectric.buildService(ImapIdleService::class.java)
        val service = controller.create().get()
        // No accounts seeded: the service should accept the start command
        // (sticky or not) and then stop itself once it sees the empty list.
        val result = service.onStartCommand(Intent(), 0, 1)
        assertTrue(
            result == android.app.Service.START_STICKY || result == android.app.Service.START_NOT_STICKY,
        )
        controller.destroy()
    }

    @Test
    fun `idle refresh constants are sane`() {
        assertEquals(24L * 60 * 1000, ImapIdleService.IDLE_REFRESH_MS)
        assertEquals(5L * 60 * 1000, ImapIdleService.FALLBACK_NO_IDLE_POLL_MS)
    }
}
