package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure lens-selection logic with no Android/CameraX dependency, so it stays
 * JVM unit-testable (mirrors `backup/.../CryptoTest.kt`: kotlin.test + JUnit4).
 *
 * The platform layer enumerates [PhysicalLens] entries; everything here only
 * reasons over that model plus per-lens capability snapshots passed in.
 */
object LensSelectionLogic {

    /** Lenses on [facing], ordered by [PhysicalLens.fallbackPriority]. */
    fun filterByFacing(lenses: List<PhysicalLens>, facing: LensFacing): List<PhysicalLens> =
        lenses.filter { it.facing == facing }.sortedBy { it.fallbackPriority }

    /**
     * Fallback ladder for a requested lens: the lens itself, then the rest of
     * the same-facing family in [PhysicalLens.fallbackPriority] order. Never
     * empty when the family is non-empty; null only when no lens of that
     * facing exists.
     */
    fun fallbackFor(requested: PhysicalLens, family: List<PhysicalLens>): PhysicalLens? {
        if (family.isEmpty()) return null
        if (requested in family) return requested
        return family.minByOrNull { it.fallbackPriority }
    }

    /**
     * Zoom-level pills for one lens: the UW floor (e.g. ".5") when the lens
     * can zoom out past 1x, always "1x", then tele stops (2x/5x) that fit in
     * [maxZoom]. Mirrors `updateZoomLevels` in `util/CameraControls.kt`.
     */
    fun buildZoomLevels(minZoom: Float, maxZoom: Float): List<Pair<String, Float>> {
        val levels = mutableListOf<Pair<String, Float>>()
        if (minZoom < 0.95f) {
            levels.add(formatZoomLabel(minZoom) to minZoom)
        }
        levels.add("1x" to 1f)
        for (tele in listOf(2f, 5f)) {
            if (tele <= maxZoom + 0.05f) levels.add(formatZoomLabel(tele) to tele)
        }
        return levels
    }

    /**
     * Display label for a lens: 35mm-equivalent focal length ("24mm") when
     * known, else the nominal zoom ratio ("0.5x"/"1x"/"3x"). Prefer
     * [LensLabels.labelsFor] for picker pills (zoom-factor style); this stays
     * for non-picker readouts and backward-compatible tests.
     */
    fun displayLabel(lens: PhysicalLens): String {
        val mm = lens.focalLengthMm35Eq
        if (mm != null && mm > 0f) return "${mm.roundToInt()}mm"
        return formatZoomLabel(lens.nominalZoomRatio)
    }

    /**
     * Human focal-length label key for strings: delegates to the lens'
     * [PhysicalLens.labelKey]; kept pure so label mapping stays testable.
     */
    fun labelKey(lens: PhysicalLens): String = lens.labelKey

    /**
     * Filters the canonical ISO stop list to the sensor's [range]
     * (inclusive). Falls back to the range endpoints when nothing canonical
     * fits, and to empty when the range is unknown — mirroring
     * `readManualControlRanges` in `util/CameraManualControls.kt`.
     */
    fun filterIsoStops(range: ClosedRange<Int>?): List<Int> {
        if (range == null) return emptyList()
        val filtered = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
            .filter { it in range }
        return filtered.ifEmpty { listOf(range.start, range.endInclusive) }
    }

    /**
     * Picks the stable fixed fps range for regular video: only fixed ranges
     * (lower == upper) avoid variable-fps sensor-mode flicker; prefer 30fps,
     * then 60fps, then the closest fixed. Null when no fixed range exists so
     * CameraX picks its default. Cinematic sticks to <= 30fps. Mirrors
     * `highestFpsRange` in `util/CameraSessions.kt`.
     */
    fun pickFpsRange(ranges: List<Pair<Int, Int>>, cinematic: Boolean): Pair<Int, Int>? {
        val fixed = ranges.filter { (lower, upper) -> lower == upper }
        if (fixed.isEmpty()) return null
        if (cinematic) {
            return fixed.firstOrNull { it.second == 30 }
                ?: fixed.filter { it.second <= 30 }.maxByOrNull { it.second }
                ?: fixed.minByOrNull { abs(it.second - 30) }
        }
        return fixed.firstOrNull { it.second == 30 }
            ?: fixed.firstOrNull { it.second == 60 }
            ?: fixed.filter { it.second <= 30 }.maxByOrNull { it.second }
            ?: fixed.filter { it.second <= 60 }.maxByOrNull { it.second }
            ?: fixed.minByOrNull { abs(it.second - 30) }
    }

    /**
     * Whether the cached night-extension failure (recorded at [failedAtMs],
     * [nowMs]) is still within the TTL ([ttlMs], default the ViewModel's
     * 7-day `NIGHT_EXT_FAILURE_TTL_MS`). True = don't offer night yet.
     */
    fun nightFailureFresh(failedAtMs: Long?, nowMs: Long, ttlMs: Long): Boolean {
        if (failedAtMs == null) return false
        return nowMs - failedAtMs < ttlMs
    }

    /** Formats a zoom ratio: ".5", "1x", "2x", "1.5x" (mirrors `formatZoomLabel`). */
    fun formatZoomLabel(ratio: Float): String = when {
        ratio < 1f -> ".${(ratio * 10).roundToInt()}"
        else -> {
            val rounded = (ratio * 10f).roundToInt() / 10f
            if (abs(rounded - rounded.roundToInt()) < 0.05f) "${rounded.roundToInt()}x"
            else "%.1fx".format(rounded)
        }
    }
}
