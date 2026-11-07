package com.vayunmathur.games.kart

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @Test fun activityLaunchesAndRoutesEventsAndDestroys() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull(activity)

        // Unmapped keys / non-joystick motion fall through to the framework (false here:
        // no seated gamepad owns them under Robolectric).
        val key = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0, 0, 999, 0)
        assertFalse(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, key))
        assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP, key))
        val coords = arrayOf(MotionEvent.PointerCoords())
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val motion = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 1, props, coords, 0, 0, 1f, 1f, 999, 0, 0, 0, 0, 0)!!
        assertFalse(activity.onGenericMotionEvent(motion))
        motion.recycle()

        controller.pause().stop().destroy()
    }
}
