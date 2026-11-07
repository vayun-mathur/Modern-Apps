package com.vayunmathur.camera.util

import androidx.camera.core.CameraInfo
import com.vayunmathur.camera.platform.toLensFacing
import com.vayunmathur.camera.platform.toSelectorInt
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class CameraSessionsExtraTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    /**
     * A CameraInfo that answers nothing: every capability query in the
     * session helpers must degrade to "unsupported" rather than throw.
     */
    private lateinit var selfProxy: Any
    private fun emptyCameraInfo(): CameraInfo = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(CameraInfo::class.java, androidx.camera.core.impl.CameraInfoInternal::class.java),
    ) { _, method, _ ->
        when {
            method.name == "getImplementation" -> selfProxy
            method.name == "getEncoderProfilesProvider" -> Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.camera.core.impl.EncoderProfilesProvider::class.java),
            ) { _, _, _ -> null }
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType == java.lang.Integer.TYPE -> 0
            method.returnType == java.lang.Float.TYPE -> 0f
            java.util.Set::class.java.isAssignableFrom(method.returnType) -> emptySet<Any>()
            java.util.List::class.java.isAssignableFrom(method.returnType) -> emptyList<Any>()
            else -> null
        }
    }.also { selfProxy = it } as CameraInfo

    @Test fun capabilityQueriesDegradeWithoutCamera2Info() {
        val v = vm()
        val info = emptyCameraInfo()
        // Recorder.getVideoCapabilities needs a real CameraX implementation
        // (it walks EncoderProfilesProvider/DynamicRange internals), so only
        // the Camera2-interop queries are exercised against the fake here.
        assertNull(v.highestFpsRange(info))
        assertNull(v.preferredStabilizationMode(info))
    }

    @Test fun flashModeMapping() {
        val v = vm()
        v.setFlashMode(FlashMode.ON)
        assertEquals(ImageCapture.FLASH_MODE_ON, v.getImageCaptureFlashMode())
        v.setFlashMode(FlashMode.OFF)
        assertEquals(ImageCapture.FLASH_MODE_OFF, v.getImageCaptureFlashMode())
        v.setFlashMode(FlashMode.AUTO)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, v.getImageCaptureFlashMode())
    }

    @Test fun teardownWithoutSessionClearsState() {
        val v = vm()
        v.isRecordingMutable.value = true
        v.isCapturingMutable.value = true
        v.timerCountdownMutable.value = 5
        v.teardownSession()
        assertFalse(v.isRecording.value)
        assertFalse(v.isCapturing.value)
        assertEquals(0, v.timerCountdown.value)
        assertEquals(0L, v.recordingDurationSec.value)
    }

    @Test fun lensFacingConversionRoundTrips() {
        val back = com.vayunmathur.camera.domain.LensFacing.BACK
        val front = com.vayunmathur.camera.domain.LensFacing.FRONT
        assertEquals(back, back.toSelectorInt().toLensFacing())
        assertEquals(front, front.toSelectorInt().toLensFacing())
        assertEquals(androidx.camera.core.CameraSelector.LENS_FACING_BACK, back.toSelectorInt())
        assertNull((999).toLensFacing())
    }
}
