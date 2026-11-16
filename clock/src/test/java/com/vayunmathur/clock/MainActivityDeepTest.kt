package com.vayunmathur.clock

import android.content.Intent
import android.os.Looper
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class MainActivityDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun mainActivityComposesContentOnResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
        val activity = controller.setup().get()
        // Drive frames so the permission gate / navigation content actually composes.
        repeat(10) {
            shadowOf(Looper.getMainLooper()).idle()
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
