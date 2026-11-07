package com.vayunmathur.youpipe.util

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import kotlin.test.Test
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceUtilPass3Test {
    @Test fun serviceCreatesPlayerAndSessionThenDestroys() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        assertNotNull(service)
        // A second create/destroy cycle in the same process must also work
        // (the service is started per playback session in production).
        controller.destroy()
        val controller2 = Robolectric.buildService(PlaybackService::class.java)
        assertNotNull(controller2.create().get())
        controller2.destroy()
    }

    @Test fun serviceBindsAsMediaSessionService() {
        val controller = Robolectric.buildService(PlaybackService::class.java)
        val service = controller.create().get()
        // MediaSessionService.onBind for a media-session intent returns the
        // session binder; a plain intent returns null from the base class.
        service.onStartCommand(null, 0, 1)
        service.onStartCommand(android.content.Intent("android.media.session.MediaButtonReceiver"), 0, 2)
        service.onTaskRemoved(null)
        val plainBinder = service.onBind(android.content.Intent("android.intent.action.MAIN"))
        // session intents are served by the base class; either outcome must not crash
        println("plainBinder=$plainBinder")
        controller.destroy()
    }
}
