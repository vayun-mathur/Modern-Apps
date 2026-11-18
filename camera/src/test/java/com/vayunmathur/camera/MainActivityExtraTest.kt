package com.vayunmathur.camera

import android.content.Intent
import android.provider.MediaStore
import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** MainActivity: capture-for-result intent, shutter key gating, result hand-back. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityExtraTest {

    @Test fun shutterKeysOnlyFireOnCameraRoute() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        // Default route is not the camera foreground route in a fresh VM, so
        // volume/space fall through to the system handling.
        assertFalse(activity.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, null))
        assertFalse(activity.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN, null))
        controller.destroy()
    }

    @Test fun captureForResultIntentDefaultsToCanceled() {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        val activity = controller.get()
        // Default result is CANCELED until a capture actually returns one.
        val shadow = org.robolectric.Shadows.shadowOf(activity)
        assertTrue(shadow.resultCode == android.app.Activity.RESULT_CANCELED)
        // finishWithCaptureResult with no thumbnail: OK result, finishes.
        val m = MainActivity::class.java.getDeclaredMethod(
            "finishWithCaptureResult", android.graphics.Bitmap::class.java,
        ).apply { isAccessible = true }
        m.invoke(activity, null)
        assertTrue(activity.isFinishing)
        assertTrue(shadow.resultCode == android.app.Activity.RESULT_OK)
    }
}
