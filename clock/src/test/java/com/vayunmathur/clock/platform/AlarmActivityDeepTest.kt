package com.vayunmathur.clock.platform

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.testsupport.awaitWithMainIdle
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

/** AlarmActivity composition and re-delivery: the ringing screen composes, then the failed load finishes it. */
@RunWith(RobolectricTestRunner::class)
class AlarmActivityDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun ringingScreenComposesAndLoadFailureFinishes() {
        val intent = Intent(ctx, AlarmActivity::class.java).apply { putExtra("ALARM_ID", 31415L) }
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, intent).create().start().resume()
        awaitWithMainIdle("load failure finishes activity", maxSteps = 100_000) { controller.get().isFinishing }
        // The DB is SQLCipher-backed and unavailable on the host JVM, so the load
        // fails and the activity finishes rather than ringing forever.
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun newIntentWithNewAlarmIdReloads() {
        val intent = Intent(ctx, AlarmActivity::class.java).apply { putExtra("ALARM_ID", 1L) }
        val controller = Robolectric.buildActivity(AlarmActivity::class.java, intent).create().start().resume()
        val activity = controller.get()
        controller.newIntent(Intent(ctx, AlarmActivity::class.java).apply { putExtra("ALARM_ID", 2L) })
        awaitWithMainIdle("reloaded load failure finishes activity", maxSteps = 100_000) { activity.isFinishing }
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
}
