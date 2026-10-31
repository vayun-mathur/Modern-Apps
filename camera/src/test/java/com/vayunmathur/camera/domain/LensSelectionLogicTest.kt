package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-domain checks for [LensSelectionLogic]: no Android/CameraX needed. */
class LensSelectionLogicTest {

    private fun lens(
        id: String,
        type: LensType,
        facing: LensFacing = LensFacing.BACK,
        priority: Int = 0,
        ratio: Float = 1f,
    ) = PhysicalLens(
        logicalCameraId = id,
        lensType = type,
        facing = facing,
        focalLengthMm35Eq = null,
        nominalZoomRatio = ratio,
        fallbackPriority = priority,
        labelKey = type.name.lowercase(),
    )

    @Test
    fun filterByFacingOrdersByPriority() {
        val lenses = listOf(
            lens("tele", LensType.TELEPHOTO, priority = 2, ratio = 3f),
            lens("uw", LensType.ULTRA_WIDE, priority = 1, ratio = 0.5f),
            lens("wide", LensType.WIDE, priority = 0),
            lens("front", LensType.FRONT, facing = LensFacing.FRONT),
        )
        val back = LensSelectionLogic.filterByFacing(lenses, LensFacing.BACK)
        assertEquals(listOf("wide", "uw", "tele"), back.map { it.logicalCameraId })
    }

    @Test
    fun fallbackPrefersRequestedThenWide() {
        val wide = lens("wide", LensType.WIDE, priority = 0)
        val uw = lens("uw", LensType.ULTRA_WIDE, priority = 1, ratio = 0.5f)
        val family = listOf(wide, uw)
        // Requested lens present → itself.
        assertEquals("uw", LensSelectionLogic.fallbackFor(uw, family)?.logicalCameraId)
        // Unknown request → same-facing default (wide, lowest fallbackPriority).
        val tele = lens("tele", LensType.TELEPHOTO, priority = 2, ratio = 3f)
        assertEquals("wide", LensSelectionLogic.fallbackFor(tele, family)?.logicalCameraId)
        assertNull(LensSelectionLogic.fallbackFor(tele, emptyList()))
    }

    @Test
    fun zoomBuilderMirrorsUpdateZoomLevels() {
        assertEquals(
            listOf(".5" to 0.5f, "1x" to 1f, "2x" to 2f, "5x" to 5f),
            LensSelectionLogic.buildZoomLevels(0.5f, 10f),
        )
        assertEquals(listOf("1x" to 1f), LensSelectionLogic.buildZoomLevels(1f, 1f))
        assertEquals(
            listOf("1x" to 1f, "2x" to 2f),
            LensSelectionLogic.buildZoomLevels(1f, 2f),
        )
    }

    @Test
    fun isoFilterKeepsCanonicalStopsInRange() {
        assertEquals(
            listOf(100, 200, 400, 800, 1600, 3200),
            LensSelectionLogic.filterIsoStops(100..3200),
        )
        assertEquals(listOf(50, 100), LensSelectionLogic.filterIsoStops(50..100))
        // Nothing canonical fits → range endpoints.
        assertEquals(listOf(75, 90), LensSelectionLogic.filterIsoStops(75..90))
        assertTrue(LensSelectionLogic.filterIsoStops(null).isEmpty())
    }

    @Test
    fun fpsPickerPrefersFixed30() {
        val ranges = listOf(30 to 60, 30 to 30, 60 to 60)
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(ranges, cinematic = false))
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(ranges, cinematic = true))
        // No fixed ranges → null so CameraX picks its default.
        assertNull(LensSelectionLogic.pickFpsRange(listOf(15 to 30, 30 to 60), cinematic = false))
        // Cinematic sticks to <= 30fps.
        assertEquals(30 to 30, LensSelectionLogic.pickFpsRange(listOf(30 to 30, 60 to 60), cinematic = true))
    }

    @Test
    fun nightFailureTtl() {
        val ttl = 7L * 24 * 60 * 60 * 1000
        val now = 1_000_000_000L
        assertTrue(LensSelectionLogic.nightFailureFresh(now - 1000, now, ttl))
        assertTrue(!LensSelectionLogic.nightFailureFresh(now - ttl - 1, now, ttl))
        assertTrue(!LensSelectionLogic.nightFailureFresh(null, now, ttl))
    }

    @Test
    fun displayLabelUsesFocalLengthWhenKnown() {
        val withMm = lens("wide", LensType.WIDE).copy(focalLengthMm35Eq = 24f)
        assertEquals("24mm", LensSelectionLogic.displayLabel(withMm))
        assertEquals("1x", LensSelectionLogic.displayLabel(lens("wide", LensType.WIDE)))
        assertEquals(".5", LensSelectionLogic.displayLabel(lens("uw", LensType.ULTRA_WIDE, ratio = 0.5f)))
    }

    @Test
    fun gs6ProPickerLabelsReadPointFourPointFiveOne() {
        // Murena GS6 Pro back triple (issue #876): pills must read ".4",
        // ".5", "1x" — never duplicated "UW" buckets.
        val family = listOf(
            lens("0", LensType.ULTRA_WIDE, ratio = 0.4f).copy(focalLengthMm35Eq = 10.8f),
            lens("1", LensType.ULTRA_WIDE, ratio = 0.5f).copy(focalLengthMm35Eq = 13.5f),
            lens("2", LensType.WIDE).copy(focalLengthMm35Eq = 27f),
        )
        assertEquals(listOf(".4", ".5", "1x"), LensLabels.labelsFor(family))
    }
}
