package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Aperture-style zoom-factor labels ([LensLabels]): no Android/CameraX needed. */
class LensLabelsTest {

    private fun lens(
        id: String,
        type: LensType,
        facing: LensFacing = LensFacing.BACK,
        eq35Mm: Float? = null,
        ratio: Float = 1f,
        intrinsic: Float? = null,
    ) = PhysicalLens(
        logicalCameraId = id,
        lensType = type,
        facing = facing,
        focalLengthMm35Eq = eq35Mm,
        nominalZoomRatio = ratio,
        intrinsicZoomRatio = intrinsic,
        fallbackPriority = 0,
        labelKey = type.name.lowercase(),
    )

    // GS6 Pro back triple: nominal 0.4x/0.5x/1x lenses (13mm/16mm/27mm-eq).
    private fun gs6Back() = listOf(
        lens("0", LensType.ULTRA_WIDE, eq35Mm = 10.8f, ratio = 0.4f),
        lens("1", LensType.ULTRA_WIDE, eq35Mm = 13.5f, ratio = 0.5f),
        lens("2", LensType.WIDE, eq35Mm = 27f, ratio = 1f),
    )

    @Test
    fun gs6ProLabelsReadPointFourPointFiveOne() {
        assertEquals(listOf(".4", ".5", "1x"), LensLabels.labelsFor(gs6Back()))
    }

    @Test
    fun pixelLikeFamilyLabels() {
        // 13mm / 27mm / 55mm / 135mm-eq ≈ 0.5x / 1x / 2x / 5x.
        val family = listOf(
            lens("uw", LensType.ULTRA_WIDE, eq35Mm = 13.5f, ratio = 0.5f),
            lens("wide", LensType.WIDE, eq35Mm = 27f, ratio = 1f),
            lens("tele2", LensType.TELEPHOTO, eq35Mm = 55f, ratio = 2f),
            lens("tele5", LensType.TELEPHOTO, eq35Mm = 135f, ratio = 5f),
        )
        assertEquals(listOf(".5", "1x", "2x", "5x"), LensLabels.labelsFor(family))
    }

    @Test
    fun zoomFactorPrefersEqRatioThenIntrinsicThenNominal() {
        // Eq-35mm ratio wins even when the nominal ratio disagrees (different
        // sensor sizes make raw-focal ratios lie).
        assertEquals(0.5f, LensLabels.zoomFactor(13.5f, 27f, 0.6f, 0.6f))
        // No eq data: the intrinsic ratio (logical multi-camera) wins.
        assertEquals(0.45f, LensLabels.zoomFactor(null, null, 0.45f, 0.6f))
        // Neither: nominal raw-focal ratio.
        assertEquals(0.6f, LensLabels.zoomFactor(null, null, null, 0.6f))
        // Nothing usable: unity rather than a divide-by-zero.
        assertEquals(1f, LensLabels.zoomFactor(null, null, null, 0f))
        assertEquals(1f, LensLabels.zoomFactor(0f, 27f, null, 0f))
    }

    @Test
    fun formatRoundsLikeTheZoomBar() {
        assertEquals(".4", LensLabels.formatZoom(0.4f))
        assertEquals(".5", LensLabels.formatZoom(0.5f))
        assertEquals("1x", LensLabels.formatZoom(1f))
        assertEquals("2x", LensLabels.formatZoom(2f))
        assertEquals("1.5x", LensLabels.formatZoom(1.5f))
        assertEquals("2.5x", LensLabels.formatZoom(2.5f))
        assertEquals("5x", LensLabels.formatZoom(5.02f))
        assertEquals("2.03x", LensLabels.formatZoomPrecise(2.03f))
        assertEquals(".50", LensLabels.formatZoomPrecise(0.504f))
        // 10x+ keeps the short form so it still fits the 36dp chip.
        assertEquals("10x", LensLabels.formatZoomPrecise(10.02f))
    }

    @Test
    fun nearCollisionGainsPrecisionInsteadOfDuplicating() {
        // 13.4mm-eq and 13.6mm-eq against a 27mm-eq wide both round to ".5";
        // the second keeps extra precision instead of colliding.
        val family = listOf(
            lens("a", LensType.ULTRA_WIDE, eq35Mm = 13.4f, ratio = 0.496f),
            lens("b", LensType.ULTRA_WIDE, eq35Mm = 13.6f, ratio = 0.504f),
            lens("wide", LensType.WIDE, eq35Mm = 27f, ratio = 1f),
        )
        val labels = LensLabels.labelsFor(family)
        assertEquals(3, labels.size)
        assertEquals(labels.distinct().size, labels.size)
        // First at each rounded value keeps the short label.
        assertEquals(".5", labels[0])
        assertEquals(".50", labels[1])
        // Genuinely identical factors stay visually adjacent (".5"/".50")
        // rather than inventing a distinction that is not there.
        val twins = listOf(
            lens("a", LensType.ULTRA_WIDE, eq35Mm = 13.5f, ratio = 0.5f),
            lens("b", LensType.MONOCHROME, eq35Mm = 13.5f, ratio = 0.5f),
            lens("wide", LensType.WIDE, eq35Mm = 27f, ratio = 1f),
        )
        assertEquals(listOf(".5", ".50", "1x"), LensLabels.labelsFor(twins))
    }

    @Test
    fun ultrawideCoveredOnlyWhenWideZoomsOutThatFar() {
        assertTrue(LensLabels.isUltrawideCovered(0.5f, 0.5f))
        assertTrue(LensLabels.isUltrawideCovered(0.5f, 0.4f))
        assertFalse(LensLabels.isUltrawideCovered(0.5f, 0.55f))
        assertFalse(LensLabels.isUltrawideCovered(0.4f, 0.5f))
        assertFalse(LensLabels.isUltrawideCovered(0.5f, 1f))
        assertFalse(LensLabels.isUltrawideCovered(0.5f, null))
        assertFalse(LensLabels.isUltrawideCovered(0.5f, Float.NaN))
    }

    @Test
    fun dedupeCollapsesRepeatedPhysicalLens() {
        // Same logical ID twice.
        val twice = listOf(
            lens("0", LensType.WIDE, eq35Mm = 27f),
            lens("0", LensType.WIDE, eq35Mm = 27f),
        )
        assertEquals(1, LensLabels.dedupe(twice).size)
        // Same characteristics under two IDs (logical + physical alias).
        val aliased = listOf(
            lens("0", LensType.ULTRA_WIDE, eq35Mm = 13.5f, ratio = 0.5f),
            lens("2", LensType.ULTRA_WIDE, eq35Mm = 13.5f, ratio = 0.5f),
            lens("1", LensType.WIDE, eq35Mm = 27f),
        )
        assertEquals(listOf("0", "1"), LensLabels.dedupe(aliased).map { it.logicalCameraId })
        // Distinct lenses survive.
        assertEquals(3, LensLabels.dedupe(gs6Back()).size)
    }

    @Test
    fun sortedByFactorOrdersAscendingAndKeepsFrontAnchor() {
        // Deliberately scrambled enumeration order → Aperture order.
        val scrambled = listOf(gs6Back()[2], gs6Back()[0], gs6Back()[1])
        assertEquals(listOf(".4", ".5", "1x"), LensLabels.labelsFor(LensLabels.sortedByFactor(scrambled)))
        // Front lenses anchor to their own FRONT reference, not the back wide.
        val mixed = gs6Back() + lens("f", LensType.FRONT, facing = LensFacing.FRONT, eq35Mm = 25f)
        val factors = LensLabels.factorsFor(mixed)
        assertEquals(1f, factors[3])
    }

    @Test
    fun emptyFamilyLabelsEmpty() {
        assertEquals(emptyList(), LensLabels.labelsFor(emptyList()))
    }

    @Test
    fun dropCoveredUltrawidesJudgesEachStop() {
        // Logical multi-camera: wide floor 0.45 reaches the 0.5x stop but
        // not the genuinely wider 0.4x lens — the latter survives.
        val gs6 = gs6Back()
        assertEquals(
            listOf(gs6[0], gs6[2]),
            LensLabels.dropCoveredUltrawides(gs6, 0.45f),
        )
        // A floor at/above every UW stop (0.4 or lower) removes both.
        assertEquals(
            listOf(gs6[2]),
            LensLabels.dropCoveredUltrawides(gs6, 0.4f),
        )
        // An unknown floor drops nothing — the pills stay authoritative.
        assertEquals(gs6, LensLabels.dropCoveredUltrawides(gs6, null))
        assertEquals(gs6, LensLabels.dropCoveredUltrawides(gs6, Float.NaN))
    }
}
