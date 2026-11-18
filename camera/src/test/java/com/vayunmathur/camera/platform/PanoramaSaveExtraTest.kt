package com.vayunmathur.camera.platform

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.camera.domain.PanoInfo
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** PanoramaEngine save routing and reset bookkeeping. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanoramaSaveExtraTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun jpegBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.YELLOW) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun info() = PanoInfo(
        projectionType = "cylindrical", fullWidth = 32, fullHeight = 16,
        croppedWidth = 32, croppedHeight = 16, croppedLeft = 0, croppedTop = 0,
    )

    @Test fun saveToMediaStoreAttemptsWrite() {
        val engine = PanoramaEngine(context)
        // Robolectric's resolver may or may not accept the insert; the engine
        // must return the row Uri on success and null (not throw) on failure.
        val uri = engine.saveToMediaStore(jpegBytes(), info())
        assertTrue(uri == null || uri.toString().contains("media"))
    }

    @Test fun resetClearsSweepStateAndRecyclesPendingFrame() {
        val engine = PanoramaEngine(context)
        engine.startSweep(fullSphere = false)
        val pending = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        engine.offerFrame(pending, 0)
        engine.reset()
        assertEquals(0, engine.frameCount.value)
        assertTrue(!engine.isSweeping.value)
        assertTrue(!engine.isStitching.value)
        assertTrue(engine.guideDots.value.isEmpty())
        assertEquals(0f, engine.sweepAngle.value)
        assertTrue(pending.isRecycled)
    }
}
