package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AE-convergence gate for ZSL captures: the ring buffer only holds converged frames,
 * so a press while AE is still searching after a lighting change would reprocess a
 * frame exposed for the previous scene (intermittent strong overexposure in auto).
 */
@RunWith(AndroidJUnit4::class)
class AeConvergenceGateTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    @OptIn(ExperimentalZeroShutterLag::class)
    private fun CameraViewModel.bindZslCapture() {
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
            .build()
    }

    // ---- Ready-state truth table ----

    @Test fun convergedAndLockedAreReady() {
        assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_CONVERGED))
        assertTrue(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_LOCKED))
    }

    @Test fun unsettledStatesAreNotReady() {
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_INACTIVE))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_SEARCHING))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED))
        assertFalse(aeStateReadyForCapture(CameraMetadata.CONTROL_AE_STATE_PRECAPTURE))
    }

    @Test fun unreportedStateDoesNotGate() {
        assertTrue(aeStateReadyForCapture(null))
    }

    // ---- Snapshot callback feeds the gate ----

    @Test fun snapshotCallbackRecordsAeStateAndValues() {
        val v = vm()
        val result = Mockito.mock(TotalCaptureResult::class.java)
        Mockito.`when`(result.get(CaptureResult.CONTROL_AE_STATE))
            .thenReturn(CameraMetadata.CONTROL_AE_STATE_SEARCHING)
        Mockito.`when`(result.get(CaptureResult.SENSOR_SENSITIVITY)).thenReturn(800)
        Mockito.`when`(result.get(CaptureResult.SENSOR_EXPOSURE_TIME)).thenReturn(33_333_333L)

        v.aeSnapshotCallback.onCaptureCompleted(
            Mockito.mock(android.hardware.camera2.CameraCaptureSession::class.java),
            Mockito.mock(android.hardware.camera2.CaptureRequest::class.java),
            result
        )

        assertEquals(CameraMetadata.CONTROL_AE_STATE_SEARCHING, v.lastAeState)
        assertEquals(800, v.lastAeIso)
        assertEquals(33_333_333L, v.lastAeExposureNanos)
        assertFalse(v.isAeConvergedForCapture())
    }

    // ---- The wait itself ----

    @Test fun awaitReturnsImmediatelyWhenConverged() = runBlocking {
        val v = vm()
        v.bindZslCapture()
        v.lastAeState = CameraMetadata.CONTROL_AE_STATE_CONVERGED
        assertTrue(v.awaitAeConvergence(timeoutMs = 5_000L))
    }

    @Test fun awaitSkipsNonZslCaptures() = runBlocking {
        val v = vm()
        // No ZSL capture bound: the regular pipeline converges on its own.
        v.lastAeState = CameraMetadata.CONTROL_AE_STATE_SEARCHING
        assertTrue(v.awaitAeConvergence(timeoutMs = 5_000L))
    }

    @Test fun awaitWaitsForConvergence() = runBlocking {
        val v = vm()
        v.bindZslCapture()
        v.lastAeState = CameraMetadata.CONTROL_AE_STATE_SEARCHING
        val waiting = async { v.awaitAeConvergence(timeoutMs = 5_000L) }
        delay(150L)
        assertFalse(waiting.isCompleted)
        v.lastAeState = CameraMetadata.CONTROL_AE_STATE_CONVERGED
        assertTrue(waiting.await())
    }

    @Test fun awaitTimesOutAndCapturesAnyway() = runBlocking {
        val v = vm()
        v.bindZslCapture()
        v.lastAeState = CameraMetadata.CONTROL_AE_STATE_SEARCHING
        val started = System.currentTimeMillis()
        assertFalse(v.awaitAeConvergence(timeoutMs = 200L))
        assertTrue(System.currentTimeMillis() - started >= 200L)
    }
}
