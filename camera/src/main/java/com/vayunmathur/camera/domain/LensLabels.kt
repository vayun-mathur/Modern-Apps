package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Aperture/Pixel-style zoom-factor labels for the physical-lens picker.
 *
 * Previously pills were derived from coarse [LensType] buckets plus a
 * 35mm-equivalent millimeter readout ("UW", "UW · 13mm"): on phones whose
 * back lenses sit close together — e.g. the Murena GS6 Pro's 0.4x/0.5x/1x
 * triple — near-spaced lenses collapsed into one bucket, so pills duplicated
 * ("UW", "UW") while the "short · mm" text overflowed the fixed 36dp chips.
 * These helpers instead compute each lens's zoom factor relative to its
 * facing's main (1x) lens and format it like Aperture/Pixel (".4", ".5",
 * "1x", "2x").
 *
 * Pure JVM (no Android/CameraX dependency); the platform layer in
 * `platform/LensSelector.kt` feeds the raw characteristics in.
 */
object LensLabels {

    /**
     * 35mm-equivalent zoom ratio of one lens against the 1x reference, or
     * null when either value is missing or unusable (so the caller falls
     * through to the intrinsic / nominal ratios).
     */
    private fun eqZoomRatio(lensEq35Mm: Float?, referenceEq35Mm: Float?): Float? {
        if (lensEq35Mm == null || referenceEq35Mm == null) return null
        if (lensEq35Mm <= 0f || referenceEq35Mm <= 0f) return null
        return lensEq35Mm / referenceEq35Mm
    }

    /**
     * Zoom factor of a lens relative to its facing's main (1x) lens.
     *
     * Prefers the 35mm-equivalent ratio (physical optics: focal length scaled
     * by each sensor's own diagonal, so lenses on different-sized sensors
     * compare correctly where raw focal-length ratios do not), then the
     * CameraX intrinsic zoom ratio, then the nominal raw-focal ratio. Falls
     * back to 1f only when every source is missing or non-positive.
     */
    fun zoomFactor(
        lensEq35Mm: Float?,
        referenceEq35Mm: Float?,
        intrinsicRatio: Float?,
        nominalRatio: Float,
    ): Float {
        val optical = eqZoomRatio(lensEq35Mm, referenceEq35Mm)
        if (optical != null) return optical
        if (intrinsicRatio != null && intrinsicRatio.isFinite() && intrinsicRatio > 0f) {
            return intrinsicRatio
        }
        if (nominalRatio.isFinite() && nominalRatio > 0f) {
            return nominalRatio
        }
        return 1f
    }

    /**
     * 35mm-equivalent focal length of the family's 1x reference (the WIDE
     * back lens, or the FRONT lens up front); null when the HAL did not
     * report a usable value, in which case every factor falls back to the
     * intrinsic/nominal ratios.
     */
    fun referenceEqMm(family: List<PhysicalLens>): Float? =
        family.firstOrNull { it.lensType == LensType.WIDE || it.lensType == LensType.FRONT }
            ?.focalLengthMm35Eq
            ?.takeIf { it.isFinite() && it > 0f }

    /**
     * Zoom factor per lens of one facing family, in input order. Handles
     * mixed-facing input by anchoring each lens to its own facing's
     * reference (front families resolve against their FRONT lens).
     */
    fun factorsFor(family: List<PhysicalLens>): List<Float> {
        val referenceByFacing = family.map { it.facing }.toSet()
            .associateWith { facing -> referenceEqMm(family.filter { it.facing == facing }) }
        return family.map {
            zoomFactor(it.focalLengthMm35Eq, referenceByFacing[it.facing], it.intrinsicZoomRatio, it.nominalZoomRatio)
        }
    }

    /**
     * One-decimal zoom label: ".4", ".5" under 1x, "1x", "2x", "2.5x" at and
     * above. Glyphs deliberately match the ZoomBar's `formatZoomLabel` so the
     * two pill rows read consistently.
     */
    fun formatZoom(ratio: Float): String {
        if (ratio < 1f) return ".${(ratio * 10f).roundToInt()}"
        val tenth = (ratio * 10f).roundToInt() / 10f
        return if (abs(tenth - tenth.roundToInt()) < 0.05f) "${tenth.roundToInt()}x"
        else "%.1fx".format(tenth)
    }

    /**
     * Extra-precision fallback for lenses that would otherwise share a label
     * (fixed two decimals: ".43", ".50", "2.03x"). Only distinct-looking
     * output is guaranteed when the factors actually differ at this
     * precision; genuinely identical factors (e.g. a mono sensor sharing the
     * wide optics) keep near-identical labels, truthfully. Factors at/above
     * 10x keep the short one-decimal form — a second decimal there would
     * overflow the fixed 36dp chip, and collisions at that range are
     * implausible in practice.
     */
    fun formatZoomPrecise(ratio: Float): String {
        if (ratio < 1f) return ".%02d".format((ratio * 100f).roundToInt().coerceIn(0, 99))
        if (ratio >= 10f) return formatZoom(ratio)
        return "%.2fx".format((ratio * 100f).roundToInt() / 100f)
    }

    /**
     * Zoom-factor label per lens of one facing family, in input order. The
     * first lens at each rounded value keeps the short label; later lenses
     * that would round to the same text get [formatZoomPrecise] instead, so
     * two pills never read identically unless their factors truly match.
     */
    fun labelsFor(family: List<PhysicalLens>): List<String> {
        if (family.isEmpty()) return emptyList()
        val factors = factorsFor(family)
        val labels = factors.map(::formatZoom).toMutableList()
        labels.indices.groupBy { labels[it] }.values.forEach { colliding ->
            colliding.drop(1).forEach { index -> labels[index] = formatZoomPrecise(factors[index]) }
        }
        return labels
    }

    /**
     * Picker order for one facing family: ascending zoom factor (".4", ".5",
     * "1x", …) like Aperture, regardless of enumeration order. Stable, so
     * equal-factor lenses keep their relative order.
     */
    fun sortedByFactor(family: List<PhysicalLens>): List<PhysicalLens> {
        if (family.size < 2) return family
        val factors = factorsFor(family)
        return family.indices.sortedBy { factors[it] }.map { family[it] }
    }

    /**
     * Whether an ultra-wide stop is already reachable by zooming out on the
     * main lens (logical multi-camera): when the wide logical camera reports
     * a minimum zoom ratio at or below the UW factor, a separate UW pill
     * would duplicate it, so the platform layer drops the UW entry (the zoom
     * bar's sub-1x floor already covers that stop).
     */
    fun isUltrawideCovered(factor: Float, wideMinZoom: Float?): Boolean {
        if (wideMinZoom == null || !wideMinZoom.isFinite()) return false
        return wideMinZoom <= factor + ULTRAWIDE_COVER_TOLERANCE
    }

    /**
     * Drops every [LensType.ULTRA_WIDE] entry whose stop the main lens
     * already serves through its zoom range (logical multi-camera): when the
     * wide logical camera reports a minimum zoom ratio at or below a UW zoom
     * factor, that UW pill would duplicate the zoom bar's sub-1x range, so
     * the platform layer drops it. Each stop is judged on its own factor, so
     * a partially covered set (e.g. the GS6 Pro's 0.4x + 0.5x against a 0.45x
     * floor) keeps its genuinely wider lens. An unknown floor (null/NaN)
     * drops nothing.
     */
    fun dropCoveredUltrawides(family: List<PhysicalLens>, wideMinZoom: Float?): List<PhysicalLens> {
        val referenceEq = referenceEqMm(family)
        return family.filterNot { lens ->
            lens.lensType == LensType.ULTRA_WIDE && isUltrawideCovered(
                zoomFactor(
                    lens.focalLengthMm35Eq,
                    referenceEq,
                    lens.intrinsicZoomRatio,
                    lens.nominalZoomRatio,
                ),
                wideMinZoom,
            )
        }
    }

    /**
     * Drops duplicate enumerations of one physical lens: same logical camera
     * ID twice, or identical characteristics (facing, type, 35mm-eq,
     * nominal ratio) surfacing under two IDs. Order-preserving.
     */
    fun dedupe(lenses: List<PhysicalLens>): List<PhysicalLens> {
        return lenses.distinctBy { it.logicalCameraId }
            .distinctBy { "${it.facing}/${it.lensType}/${it.focalLengthMm35Eq}/${it.nominalZoomRatio}" }
    }
}

/** Slack for float noise when comparing a UW factor against the wide zoom floor. */
private const val ULTRAWIDE_COVER_TOLERANCE = 0.02f
