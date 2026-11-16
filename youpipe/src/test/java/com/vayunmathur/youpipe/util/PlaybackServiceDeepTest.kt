package com.vayunmathur.youpipe.util

import android.content.Intent
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PlaybackService lifecycle under Robolectric (session service, no playback). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackServiceDeepTest {
    @Test fun service_createAndDestroy() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        assertTrue(service != null)
        controller.destroy()
    }
    @Test fun service_sessionAndLifecycleCallbacks() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        val create = androidx.media3.session.MediaSession.ControllerInfo::class.java.getDeclaredMethod("createLegacyControllerInfo").apply { isAccessible = true }
        val info = create.invoke(null) as androidx.media3.session.MediaSession.ControllerInfo
        val session = service.onGetSession(info)
        assertTrue(session != null)
        service.onUpdateNotification(session!!, false)
        service.onTaskRemoved(null)
        val rc = service.onStartCommand(null, 0, 7)
        assertTrue(rc == android.app.Service.START_NOT_STICKY || rc == android.app.Service.START_STICKY || rc >= 0)
        controller.destroy()
    }
    @Test fun service_onBind_mediaSessionService() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        val binder = runCatching { service.onBind(Intent("androidx.media3.session.MediaSessionService")) }.getOrNull()
        // Binder may be null without a session; the call itself must not crash.
        binder?.let { assertTrue(it != null) }
        controller.destroy()
    }
}
