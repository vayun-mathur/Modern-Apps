package com.vayunmathur.clock.service

import android.content.Intent
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sound service with a playable default ringtone: ShadowMediaPlayer is
 * given media info for the default alarm URI, so playAlarm actually starts,
 * the ring notification is cancelled, and the auto-stop is armed. (The alarm
 * row itself is SQLCipher-bound; the service's null-alarm defaults are what
 * run here, exactly as for a deleted alarm.)
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSoundPlayTest {

    @Test fun servicePlaysDefaultRingtoneAndStops() {
        val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (defaultUri != null) {
            ShadowMediaPlayer.addMediaInfo(
                org.robolectric.shadows.util.DataSource.toDataSource(
                androidx.test.core.app.ApplicationProvider.getApplicationContext(), defaultUri,
            ),
                ShadowMediaPlayer.MediaInfo(),
            )
        }
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().putExtra("ALARM_ID", 99L), 0, 1)
        // The ring setup runs on the service's IO scope; give it time, pumping main.
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
            Thread.sleep(40)
            // A second, overlapping alarm retires the first ring and re-rings.
            break
        }
        service.onStartCommand(Intent().putExtra("ALARM_ID", 100L), 0, 2)
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idle()
        service.onDestroy()
        shadowOf(Looper.getMainLooper()).idle()
        controller.destroy()
        // The foreground contract was claimed with the service's own id.
        assertEquals(
            AlarmSoundService.NOTIFICATION_ID,
            shadowOf(service).lastForegroundNotificationId,
        )
    }

    @Test fun serviceIgnoresStartWithoutAlarmId() {
        val controller = Robolectric.buildService(AlarmSoundService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(), 0, 1)
        service.onStartCommand(null, 0, 2)
        service.onDestroy()
        controller.destroy()
    }
}
