package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
import android.hardware.camera2.CameraMetadata
import android.util.Size
import com.vayunmathur.camera.platform.CameraMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawNightPolicyTest {

    private fun rawInput(
        night: Boolean = true,
        mode: CameraMode = CameraMode.PHOTO,
        supported: Boolean = true,
        ring: Boolean = true,
    ) = RawNightInput(
        mode = mode,
        nightRoute = night, rawSupported = supported, hasRawRingOutput = ring,
    )

    @Test fun rawNightNeedsNightPhotoRawAndRing() {
        assertTrue(RawNightPolicy.usesRawNight(rawInput()))
        assertNull(RawNightPolicy.exclusionReason(rawInput()))
        // Every requirement removed individually disables RAW; there is no
        // separate gate or explicit-selection switch left to turn off.
        assertFalse(RawNightPolicy.usesRawNight(rawInput(night = false)))
        assertFalse(RawNightPolicy.usesRawNight(rawInput(supported = false)))
        assertFalse(RawNightPolicy.usesRawNight(rawInput(ring = false)))
        assertFalse(RawNightPolicy.usesRawNight(rawInput(mode = CameraMode.PORTRAIT)))
        assertTrue(
            RawNightPolicy.exclusionReason(rawInput(night = false))
                .orEmpty().contains("not a night capture"),
        )
        assertTrue(
            RawNightPolicy.exclusionReason(rawInput(supported = false))
                .orEmpty().contains("RAW not supported"),
        )
        assertTrue(
            RawNightPolicy.exclusionReason(rawInput(ring = false))
                .orEmpty().contains("no RAW ring output"),
        )
    }

    private fun rawSnapshot(
        capabilities: Set<Int> = setOf(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
        sizes: Map<Int, List<Size>> = mapOf(
            ImageFormat.RAW_SENSOR to listOf(Size(4032, 3024), Size(1920, 1440)),
            ImageFormat.RAW10 to listOf(Size(4032, 3024)),
        ),
        black: List<Int> = listOf(64, 64, 64, 64),
        white: Int? = 1023,
        cfa: Int? = CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB,
        matrices: List<Float> = List(9) { 0.5f },
        illuminant2: Int? = 0x15, // STANDARD_A-ish, any non-null code
    ) = CharacteristicsFacade.fromFakeMap(
        cameraId = "0",
        sizesByFormat = sizes,
        availableCapabilities = capabilities,
        colorFilterArrangement = cfa,
        activeArrayWidth = 4032,
        activeArrayHeight = 3024,
        rawBlackLevelPattern = black,
        rawWhiteLevel = white,
        rawColorTransform1 = matrices,
        rawColorTransform2 = matrices,
        rawCalibrationTransform1 = matrices,
        rawCalibrationTransform2 = matrices,
        rawForwardMatrix1 = matrices,
        rawForwardMatrix2 = matrices,
        rawReferenceIlluminant1 = 0x15,
        rawReferenceIlluminant2 = illuminant2,
    ).snapshot

    @Test fun facadePrefersRawSensorAndSelectsCappedSize() {
        val snap = rawSnapshot()
        assertTrue(snap.supportsRaw)
        assertEquals(RingFormat.RAW_SENSOR, snap.rawRingFormat)
        assertTrue(snap.supportsRawRing)
        assertEquals(FrameSize(1920, 1440), StreamSelection.selectRawRingSize(snap))
        // No RAW capability: a RAW stream size alone never proves support.
        val noCap = rawSnapshot(capabilities = emptySet())
        assertNull(noCap.rawRingFormat)
        assertFalse(noCap.supportsRawRing)
        // RAW10-only lens falls back to the packed format.
        val raw10Only = rawSnapshot(
            sizes = mapOf(ImageFormat.RAW10 to listOf(Size(1920, 1440))),
        )
        assertEquals(RingFormat.RAW10, raw10Only.rawRingFormat)
        assertEquals(10, RingFormat.RAW10.rawBitDepth())
        assertEquals(ImageFormat.RAW10, RingFormat.RAW10.toImageFormat())
    }

    @Test fun dngAssemblyRequiresEverySensorField() {
        val complete = rawSnapshot()
        assertTrue(DngMetadataAssembler.missingRequirements(complete).isEmpty())
        val assembled = assertNotNull(DngMetadataAssembler.assemble(complete))
        assertEquals(listOf(64, 64, 64, 64), assembled.blackLevelPattern)
        assertEquals(1023, assembled.whiteLevel)
        assertEquals(9, assembled.colorTransform1.size)

        val noWhite = rawSnapshot(white = null)
        assertNull(DngMetadataAssembler.assemble(noWhite))
        assertTrue(
            DngMetadataAssembler.missingRequirements(noWhite)
                .any { it.contains("WHITE_LEVEL") },
        )
        val shortBlack = rawSnapshot(black = listOf(64, 64))
        assertNull(DngMetadataAssembler.assemble(shortBlack))
        val noCfa = rawSnapshot(cfa = null)
        assertNull(DngMetadataAssembler.assemble(noCfa))
        val halfSecondIlluminant = rawSnapshot(illuminant2 = null)
        assertNull(DngMetadataAssembler.assemble(halfSecondIlluminant))
    }

    @Test fun latestCompleteBurstReturnsOldestFirstCompleteFrames() {
        val ring = ReprocessRing<String>(capacity = 8)
        fun meta(ts: Long, exp: Long?) = RingFrameMeta(
            sensorTimestampNs = ts, exposureTimeNs = exp, format = RingFormat.RAW_SENSOR,
        )
        ring.offer(meta(100, 10), "a")
        ring.offer(meta(200, 10), "b")
        ring.offer(meta(300, 10), "c")
        ring.offer(meta(400, 500), "still-exposing") // ends 900
        val burst = ring.latestCompleteBurst(2, atNs = 350)
        assertEquals(listOf("b", "c"), burst.map { it.first })
        assertEquals(listOf(200L, 300L), burst.map { it.second.sensorTimestampNs })
        assertEquals(3, ring.latestCompleteBurst(6, atNs = 350).size)
        assertTrue(ring.latestCompleteBurst(0, atNs = 350).isEmpty())
    }
}
