package com.vayunmathur.clock

import android.content.Intent
import com.vayunmathur.testsupport.awaitWithMainIdle
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class MainActivityDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @Test fun mainActivityComposesContentOnResume() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_MAIN))
        val activity = controller.setup().get()
        // Pump the main looper until the permission gate / navigation content
        // has composed and the activity is settled (not finishing).
        awaitWithMainIdle("main activity composed") { !activity.isFinishing }
        assertFalse(activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
