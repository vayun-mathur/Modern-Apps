package com.vayunmathur.email.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BootReceiver]: boot/package-replaced must defer the IDLE start via the
 * pending flag (starting a foreground service from boot crashes on S+);
 * unrelated broadcasts must do nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BootReceiverTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun prefs() = context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    @BeforeTest
    fun setUp() {
        try {
            WorkManager.initialize(context, Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        prefs().edit { remove(BootReceiver.KEY_PENDING) }
    }

    @Test
    fun `unrelated action sets no pending flag`() {
        BootReceiver().onReceive(context, Intent("com.example.UNRELATED"))
        assertFalse(prefs().getBoolean(BootReceiver.KEY_PENDING, false))
    }

    @Test
    fun `boot completed sets the deferred idle pending flag`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        repeat(200) {
            if (prefs().getBoolean(BootReceiver.KEY_PENDING, false)) return@repeat
            Thread.sleep(10)
        }
        assertTrue(prefs().getBoolean(BootReceiver.KEY_PENDING, false))
    }
}
