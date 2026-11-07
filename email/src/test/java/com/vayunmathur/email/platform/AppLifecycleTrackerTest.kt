package com.vayunmathur.email.platform

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AppLifecycleTracker.tryStartIdleIfForeground]: the deferred IDLE restart
 * must only fire in the foreground with the boot pending flag set, and a
 * successful start must consume the flag so it does not loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLifecycleTrackerTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private fun prefs() = context.getSharedPreferences(BootReceiver.PREFS, Context.MODE_PRIVATE)

    @AfterTest
    fun reset() {
        AppLifecycleTracker.isAppInForeground = false
        prefs().edit { remove(BootReceiver.KEY_PENDING) }
    }

    @Test
    fun `background app does not attempt idle start`() {
        AppLifecycleTracker.isAppInForeground = false
        prefs().edit { putBoolean(BootReceiver.KEY_PENDING, true) }
        assertFalse(AppLifecycleTracker.tryStartIdleIfForeground(context))
        assertTrue(prefs().getBoolean(BootReceiver.KEY_PENDING, false))
    }

    @Test
    fun `foreground without pending flag does not attempt`() {
        AppLifecycleTracker.isAppInForeground = true
        prefs().edit { remove(BootReceiver.KEY_PENDING) }
        assertFalse(AppLifecycleTracker.tryStartIdleIfForeground(context))
    }

    @Test
    fun `foreground with pending flag attempts start and clears it`() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: IllegalStateException) {
        }
        AppLifecycleTracker.isAppInForeground = true
        prefs().edit { putBoolean(BootReceiver.KEY_PENDING, true) }
        assertTrue(AppLifecycleTracker.tryStartIdleIfForeground(context))
        // Robolectric cannot actually start a foreground service, so the
        // direct start reports failure: the flag then stays set and the
        // retry worker is scheduled instead. Either outcome proves the
        // attempt was made; a silent no-op would leave both untouched.
        val flagCleared = !prefs().getBoolean(BootReceiver.KEY_PENDING, false)
        val retryScheduled = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("ImapIdleRetryWorker").get()
            .any { it.state != androidx.work.WorkInfo.State.CANCELLED }
        assertTrue(flagCleared || retryScheduled)
    }
}
