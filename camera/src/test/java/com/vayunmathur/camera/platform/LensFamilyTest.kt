package com.vayunmathur.camera.platform

import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass 3: facing-family classification in LensSelector (via the RawLens seam). */
class LensFamilyTest {

    private fun raw(
        id: String, facing: LensFacing, focal: Float, eq: Float? = null,
        mono: Boolean = false, minZoom: Float? = null,
    ) = RawLens(id, facing, focal, eq, mono, intrinsicZoomRatio = null, minZoomRatio = minZoom)

    @Test fun facingConversions() {
        assertEquals(
            androidx.camera.core.CameraSelector.LENS_FACING_BACK, LensFacing.BACK.toSelectorInt())
        assertEquals(
            androidx.camera.core.CameraSelector.LENS_FACING_FRONT, LensFacing.FRONT.toSelectorInt())
        assertEquals(LensFacing.BACK, androidx.camera.core.CameraSelector.LENS_FACING_BACK.toLensFacing())
        assertEquals(LensFacing.FRONT, androidx.camera.core.CameraSelector.LENS_FACING_FRONT.toLensFacing())
        assertEquals(null, androidx.camera.core.CameraSelector.LENS_FACING_EXTERNAL.toLensFacing())
        assertEquals(null, 99.toLensFacing())
    }

    @Test fun emptyAndSingleLensFamilies() {
        assertTrue(buildFamily(LensFacing.BACK, emptyList()).isEmpty())
        assertTrue(buildFamily(LensFacing.BACK, listOf(raw("f", LensFacing.FRONT, 3f))).isEmpty())
        val single = buildFamily(LensFacing.BACK, listOf(raw("0", LensFacing.BACK, 4f, eq = 27f)))
        assertEquals(1, single.size)
        assertEquals(LensType.WIDE, single[0].lensType)
        assertEquals("wide", single[0].labelKey)
        assertEquals(0, single[0].fallbackPriority)
        assertEquals(1f, single[0].nominalZoomRatio)
    }

    @Test fun threeLensBackFamilyClassifiesByFocal() {
        val family = buildFamily(
            LensFacing.BACK,
            listOf(
                raw("uw", LensFacing.BACK, 2f, eq = 14f),
                raw("wide", LensFacing.BACK, 4f, eq = 27f),
                raw("tele", LensFacing.BACK, 8f, eq = 54f)))
        val byId = family.associateBy { it.logicalCameraId }
        assertEquals(LensType.ULTRA_WIDE, byId["uw"]?.lensType)
        assertEquals("ultrawide", byId["uw"]?.labelKey)
        assertEquals(1, byId["uw"]?.fallbackPriority)
        assertEquals(LensType.WIDE, byId["wide"]?.lensType)
        assertEquals(LensType.TELEPHOTO, byId["tele"]?.lensType)
        assertEquals("tele", byId["tele"]?.labelKey)
        assertEquals(2, byId["tele"]?.fallbackPriority)
        assertEquals(0.5f, byId["uw"]?.nominalZoomRatio)
        assertEquals(2f, byId["tele"]?.nominalZoomRatio)
    }

    @Test fun frontFamilyReferenceIsFrontType() {
        val family = buildFamily(
            LensFacing.FRONT, listOf(raw("selfie", LensFacing.FRONT, 3f, eq = 25f)))
        assertEquals(LensType.FRONT, family[0].lensType)
        assertEquals("front", family[0].labelKey)
        assertEquals(0, family[0].fallbackPriority)
    }

    @Test fun monoWinsOverFocalHeuristic() {
        val family = buildFamily(
            LensFacing.BACK,
            listOf(
                raw("wide", LensFacing.BACK, 4f, eq = 27f),
                raw("mono", LensFacing.BACK, 4f, eq = 27f, mono = true)))
        val mono = family.first { it.logicalCameraId == "mono" }
        assertEquals(LensType.MONOCHROME, mono.lensType)
        assertEquals("mono", mono.labelKey)
        assertEquals(3, mono.fallbackPriority)
    }

    @Test fun nearWideLensStaysWide() {
        // 3.6mm vs 4mm reference = 0.9 ratio, above the 0.85 UW threshold.
        val family = buildFamily(
            LensFacing.BACK,
            listOf(
                raw("wide", LensFacing.BACK, 4f, eq = 27f),
                raw("near", LensFacing.BACK, 3.6f, eq = 24f)))
        assertEquals(LensType.WIDE, family.first { it.logicalCameraId == "near" }.lensType)
    }

    @Test fun coveredUltrawideIsDropped() {
        // Wide's min zoom 0.5 covers the 0.5x UW stop: no separate pill.
        val family = buildFamily(
            LensFacing.BACK,
            listOf(
                raw("uw", LensFacing.BACK, 2f, eq = 14f),
                raw("wide", LensFacing.BACK, 4f, eq = 27f, minZoom = 0.5f)))
        assertEquals(listOf("wide"), family.map { it.logicalCameraId })
        // Without the zoom-out reach, the UW pill remains.
        val kept = buildFamily(
            LensFacing.BACK,
            listOf(
                raw("uw", LensFacing.BACK, 2f, eq = 14f),
                raw("wide", LensFacing.BACK, 4f, eq = 27f, minZoom = 1f)))
        assertEquals(setOf("uw", "wide"), kept.map { it.logicalCameraId }.toSet())
    }
}
