package com.vayunmathur.measure.platform

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.util.SizeF
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowCameraCharacteristics

@RunWith(RobolectricTestRunner::class)
class CameraIntrinsicsTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val analysis = Size(640, 480)

    private fun addCamera(
        id: String = "0",
        facing: Int = CameraCharacteristics.LENS_FACING_BACK,
        configure: ShadowCameraCharacteristics.() -> Unit = {},
    ) {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        val shadow = shadowOf(chars)
        shadow.set(CameraCharacteristics.LENS_FACING, facing)
        shadow.configure()
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        shadowOf(manager).addCamera(id, chars)
    }

    private fun ShadowCameraCharacteristics.activeArray(w: Int, h: Int) {
        set(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE, Rect(0, 0, w, h))
    }

    @Test fun noCamerasResolvesNull() {
        assertNull(CameraIntrinsicsResolver.resolve(context, analysis))
        assertFalse(CameraIntrinsicsResolver.timestampsAlignWithSensors(context))
    }

    @Test fun factoryCalibrationScalesToTheAnalysisSize() {
        addCamera {
            activeArray(4000, 3000)
            set(
                CameraCharacteristics.LENS_INTRINSIC_CALIBRATION,
                floatArrayOf(3000f, 3100f, 2000f, 1500f),
            )
            set(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            )
        }
        val intr = CameraIntrinsicsResolver.resolve(context, analysis)
        assertNotNull(intr)
        assertTrue(intr.calibrated)
        // Scale is per-axis: 640/4000 and 480/3000 are both 0.16.
        assertEquals(480.0, intr.fx, 0.001)
        assertEquals(496.0, intr.fy, 0.001)
        assertEquals(320.0, intr.cx, 0.001)
        assertEquals(240.0, intr.cy, 0.001)
        assertTrue(CameraIntrinsicsResolver.timestampsAlignWithSensors(context))
    }

    @Test fun focalFallbackWhenNoCalibrationTag() {
        addCamera {
            activeArray(4000, 3000)
            set(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS, floatArrayOf(4.0f))
            set(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE, SizeF(5.0f, 3.75f))
            set(
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
            )
        }
        val intr = CameraIntrinsicsResolver.resolve(context, analysis)
        assertNotNull(intr)
        assertFalse(intr.calibrated)
        // fx = 4mm / 5mm * 4000 * (640/4000) = 512; centre is the principal point.
        assertEquals(512.0, intr.fx, 0.001)
        assertEquals(320.0, intr.cx, 0.001)
        assertEquals(240.0, intr.cy, 0.001)
        assertFalse(CameraIntrinsicsResolver.timestampsAlignWithSensors(context))
    }

    @Test fun degenerateInputsResolveNull() {
        // Calibration present but too short to contain fx/fy/cx/cy, and no
        // focal fallback data either.
        addCamera(id = "0") {
            activeArray(4000, 3000)
            set(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, floatArrayOf(3000f, 3100f))
        }
        assertNull(CameraIntrinsicsResolver.resolve(context, analysis))

        // A front camera alone is not picked as the default.
        addCamera(id = "1", facing = CameraCharacteristics.LENS_FACING_FRONT) {
            activeArray(4000, 3000)
            set(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, floatArrayOf(3000f, 3100f, 2000f, 1500f))
        }
        // Explicit id still resolves; the default search skips the front one
        // and uses the back camera added first (whose data is degenerate).
        assertNull(CameraIntrinsicsResolver.resolve(context, analysis))
    }

    @Test fun explicitCameraIdIsHonoured() {
        addCamera(id = "7") {
            activeArray(2000, 2000)
            set(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION, floatArrayOf(1500f, 1500f, 1000f, 1000f))
        }
        val intr = CameraIntrinsicsResolver.resolve(context, analysis, cameraId = "7")
        assertNotNull(intr)
        assertEquals(480.0, intr.fx, 0.001)
        assertEquals(320.0, intr.cx, 0.001)
        assertNull(CameraIntrinsicsResolver.resolve(context, analysis, cameraId = "missing"))
    }

    @Test fun intrinsicsDataClass() {
        val a = Intrinsics(1.0, 2.0, 3.0, 4.0, calibrated = true)
        assertEquals(a, a.copy())
        assertEquals(2.0, a.copy(fy = 2.0).fy)
        assertTrue(a.toString().contains("calibrated=true"))
    }
}
