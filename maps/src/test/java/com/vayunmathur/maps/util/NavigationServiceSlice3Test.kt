package com.vayunmathur.maps.util

import android.app.Service
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 (root package files M-Z): [NavigationService]'s binding and
 * start-command contract.
 *
 * The service is started for real under Robolectric: a plain start returns
 * sticky and posts the idle notification, the stop action tears the session
 * down, and nothing binds. Live navigation (GPS fixes driving the
 * notification/TTS) needs the emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavigationServiceSlice3Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun service(): NavigationService =
        Robolectric.buildService(NavigationService::class.java).create().get()

    @Test
    fun `nothing binds`() {
        val svc = service()
        try {
            assertNull(svc.onBind(Intent()))
        } finally {
            svc.onDestroy()
        }
    }

    @Test
    fun `plain start is sticky`() {
        val svc = service()
        try {
            val result = svc.onStartCommand(Intent(context, NavigationService::class.java), 0, 1)
            assertEquals(Service.START_STICKY, result)
        } finally {
            svc.onDestroy()
        }
    }

    @Test
    fun `stop action is not sticky and ends the session`() {
        val svc = service()
        try {
            svc.onStartCommand(Intent(context, NavigationService::class.java), 0, 1)
            val result = svc.onStartCommand(
                Intent(context, NavigationService::class.java)
                    .setAction(NavigationService.ACTION_STOP),
                0,
                1,
            )
            assertEquals(Service.START_NOT_STICKY, result)
        } finally {
            svc.onDestroy()
        }
    }
}
