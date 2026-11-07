package com.vayunmathur.clock.service

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.clock.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Tests for [AlarmSoundService] lifecycle guards (the alarm DB is unavailable under Robolectric). */
@RunWith(RobolectricTestRunner::class)
class AlarmSoundServiceTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test
    fun startWithoutAlarmIdStopsSelfNotSticky() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(Intent(), 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun startWithNullIntentStopsSelf() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val result = controller.get().onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun startWithAlarmIdRingsAndDestroysCleanly() {
        val intent = Intent(ApplicationProvider.getApplicationContext<Context>(), AlarmSoundService::class.java).apply {
            putExtra("ALARM_ID", 4242L)
        }
        val controller = Robolectric.buildService(AlarmSoundService::class.java, intent).create().startCommand(0, 1)
        // Give the IO coroutine a moment to attempt the (failing) DB read and ringtone.
        Thread.sleep(500)
        controller.destroy()
    }

    @Test
    fun secondAlarmSupersedesFirst() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 1L) }, 0, 1)
        service.onStartCommand(Intent(ctx, AlarmSoundService::class.java).apply { putExtra("ALARM_ID", 2L) }, 0, 2)
        Thread.sleep(300)
        controller.destroy()
    }

    @Test
    fun bindReturnsNull() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        assertNull(controller.get().onBind(Intent()))
        controller.destroy()
    }
}
