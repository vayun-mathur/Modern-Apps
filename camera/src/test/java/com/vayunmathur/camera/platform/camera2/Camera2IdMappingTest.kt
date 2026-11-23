package com.vayunmathur.camera.platform.camera2

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/**
 * The stale-id crash seam: a selected lens contributes only its facing;
 * the Camera2 id is resolved from `CameraManager.cameraIdList` entries
 * (via [CharacteristicsFacade.cameraIdFacings]) by `LENS_FACING` match.
 */
class Camera2IdMappingTest {

    // Pixel-9-Pro-XL-shaped list: logical back "0", front logical "1" is
    // NOT what CameraManager lists here — the front logical is "5" and the
    // back physical sub-cameras "2"/"3" follow the logical "0". A stale
    // enumeration can hand the preview id "1" for the selected lens; that
    // id must never be opened.
    private val pixelShaped = listOf(
        CameraIdFacing("0", LensFacing.BACK),
        CameraIdFacing("2", LensFacing.BACK),
        CameraIdFacing("3", LensFacing.BACK),
        CameraIdFacing("5", LensFacing.FRONT),
    )

    @Test fun backFacingResolvesToFirstListedBackId() {
        assertEquals(
            Camera2IdResolution.Resolved("0"),
            Camera2IdResolver.resolve(LensFacing.BACK, pixelShaped),
        )
    }

    @Test fun frontFacingResolvesToListedFrontIdNotStaleId() {
        // The selected front lens's stale derived id was "1"; the only id
        // the Camera2 stack may open is the listed front id "5".
        val resolution = Camera2IdResolver.resolve(LensFacing.FRONT, pixelShaped)
        assertEquals(Camera2IdResolution.Resolved("5"), resolution)
    }

    @Test fun unknownFacingIsErrorPathNotException() {
        val resolution = Camera2IdResolver.resolve(null, pixelShaped)
        assertTrue(resolution is Camera2IdResolution.Unavailable)
        assertTrue((resolution as Camera2IdResolution.Unavailable).reason.contains("no lens facing"))
    }

    @Test fun facingWithNoListedMatchIsUnavailable() {
        val backOnly = listOf(CameraIdFacing("0", LensFacing.BACK))
        val resolution = Camera2IdResolver.resolve(LensFacing.FRONT, backOnly)
        assertTrue(resolution is Camera2IdResolution.Unavailable)
        assertTrue((resolution as Camera2IdResolution.Unavailable).reason.contains("FRONT"))
    }

    @Test fun emptyCameraIdListIsUnavailable() {
        assertTrue(
            Camera2IdResolver.resolve(LensFacing.BACK, emptyList())
                is Camera2IdResolution.Unavailable,
        )
    }

    @Test fun unreadableAndBlankEntriesAreNeverSelected() {
        val entries = listOf(
            CameraIdFacing("", LensFacing.BACK),
            CameraIdFacing("9", null),
            CameraIdFacing("4", LensFacing.BACK),
        )
        assertEquals(
            Camera2IdResolution.Resolved("4"),
            Camera2IdResolver.resolve(LensFacing.BACK, entries),
        )
    }

    @Test fun onceErrorReporterForwardsExactlyOnce() {
        val received = mutableListOf<String>()
        val gate = OnceErrorReporter { received += it }
        assertFalse(gate.hasReported)
        assertTrue(gate.report("init failed"))
        assertTrue(gate.hasReported)
        assertFalse(gate.report("configure failed"))
        assertFalse(gate.report("open failed"))
        assertEquals(listOf("init failed"), received)
    }

    @Test fun initFailureLatchesBindAndBlocksCamera2ForThatBind() {
        // Composition/init failure path: the holder reports its pending
        // init error through the one-shot gate into the fallback latch.
        val latch = Camera2FallbackLatch()
        val bindKey = Camera2FallbackLatch.bindKey("1", CameraMode.PHOTO)
        var sinkCalls = 0
        val gate = OnceErrorReporter {
            sinkCalls++
            latch.recordFailure(bindKey)
        }
        // Repeated reports (listener re-entry / recomposition) signal once.
        repeat(60) { gate.report("getCameraCharacteristics: unknown device 1") }
        assertEquals(1, sinkCalls)
        assertTrue(latch.isLatched(bindKey))
        assertFalse(
            PreviewEnginePolicy.usesCamera2(
                CameraMode.PHOTO,
                fallbackLatched = latch.isLatched(bindKey),
            ),
            "latched init failure must block this bind until retry",
        )
        // A different bind (other lens) is a fresh attempt.
        val otherKey = Camera2FallbackLatch.bindKey("5", CameraMode.PHOTO)
        assertFalse(latch.isLatched(otherKey))
        assertTrue(
            PreviewEnginePolicy.usesCamera2(
                CameraMode.PHOTO,
                fallbackLatched = latch.isLatched(otherKey),
            ),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CharacteristicsFacadeMappingTest {

    private fun characteristicsWithFacing(facing: Int): CameraCharacteristics {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        Shadows.shadowOf(chars).set(CameraCharacteristics.LENS_FACING, facing)
        return chars
    }

    private fun managerWith(vararg cameras: Pair<String, Int>): CameraManager {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(CameraManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        for ((id, facing) in cameras) {
            shadow.addCamera(id, characteristicsWithFacing(facing))
        }
        return manager
    }

    @Test fun cameraIdFacingsEnumeratesOnlyListedIdsWithFacing() {
        val manager = managerWith(
            "0" to CameraMetadata.LENS_FACING_BACK,
            "2" to CameraMetadata.LENS_FACING_BACK,
            "5" to CameraMetadata.LENS_FACING_FRONT,
        )
        assertEquals(
            listOf(
                CameraIdFacing("0", LensFacing.BACK),
                CameraIdFacing("2", LensFacing.BACK),
                CameraIdFacing("5", LensFacing.FRONT),
            ),
            CharacteristicsFacade.cameraIdFacings(manager),
        )
        assertEquals("0", CharacteristicsFacade.resolveCamera2Id(manager, LensFacing.BACK))
        assertEquals("5", CharacteristicsFacade.resolveCamera2Id(manager, LensFacing.FRONT))
        assertNull(CharacteristicsFacade.resolveCamera2Id(manager, null))
    }

    @Test fun tryFromCameraManagerReturnsNullForUnknownDeviceId() {
        val manager = managerWith("0" to CameraMetadata.LENS_FACING_BACK)
        // The device crash: "1" is not in cameraIdList on this device shape.
        assertNull(CharacteristicsFacade.tryFromCameraManager(manager, "1"))
        assertNotNull(CharacteristicsFacade.tryFromCameraManager(manager, "0"))
    }
}
