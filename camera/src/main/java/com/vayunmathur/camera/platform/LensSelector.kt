package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import com.vayunmathur.library.log.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensLabels
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.util.CameraViewModel
import kotlin.math.sqrt

/** Maps the pure [LensFacing] to the CameraX selector constant. */
fun LensFacing.toSelectorInt(): Int = when (this) {
    LensFacing.BACK -> CameraSelector.LENS_FACING_BACK
    LensFacing.FRONT -> CameraSelector.LENS_FACING_FRONT
}

/** Maps a CameraX lens-facing constant to [LensFacing]; null for external/unknown. */
fun Int.toLensFacing(): LensFacing? = when (this) {
    CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
    CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
    else -> null
}

/**
 * Enumerates every physical/logical lens via [ProcessCameraProvider.availableCameraInfos]
 * plus `Camera2CameraInfo` interop (camera ID, `LENS_FACING`, focal lengths, sensor
 * physical size for the 35mm-equivalent label, mono color-filter detection).
 *
 * Types are assigned per facing family sorted by focal length: the lens whose
 * 35mm-equivalent is closest to ~27mm is the wide reference (1x); shorter is
 * ultra-wide, longer is telephoto; a MONO color-filter arrangement wins over the
 * focal heuristic. Front-facing families mark their reference lens FRONT.
 *
 * Safe to call off the main thread; never throws (returns empty on failure).
 */
fun enumerateLenses(provider: ProcessCameraProvider): List<PhysicalLens> {
    val raws = provider.availableCameraInfos.mapNotNull { info -> readRawLens(info) }

    // Logical multi-camera (e.g. a UW stop reachable via the wide camera's
    // zoom range) plus same-physical-lens duplicates would each render an
    // extra identical pill — `buildFamily` handles the former per facing,
    // and the dedupe collapses the latter (issue #876).
    return LensLabels.dedupe(
        LensFacing.entries.flatMap { facing -> buildFamily(facing, raws) }
    )
}

/** Full-frame diagonal (mm) of 35mm film; wide-reference eq focal length + family ratios. */
private const val FULL_FRAME_DIAGONAL_MM = 43.27f
private const val WIDE_REFERENCE_EQ_MM = 27f
private const val ULTRA_WIDE_RATIO = 0.85f
private const val TELEPHOTO_RATIO = 1.4f

/** Reads one camera info's physical-lens description; null when unusable. */
private fun readRawLens(info: androidx.camera.core.CameraInfo): RawLens? {
    return try {
        readRawLensOrThrow(info)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Skipping camera info during lens enumeration", e)
        null
    }
}

/** Physical-lens description probed off one CameraInfo (null for external/unknown lenses). */
@Suppress("DEPRECATION")
private fun readRawLensOrThrow(info: androidx.camera.core.CameraInfo): RawLens? {
    val cam2 = Camera2CameraInfo.from(info)
    val facing = when (
        cam2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
    ) {
        CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
        // External / unknown: not a phone lens.
        else -> return null
    }
    val focal = cam2.getCameraCharacteristic(
        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
    )?.firstOrNull() ?: return null
    val sensorSize = cam2.getCameraCharacteristic(
        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
    )
    val eq35 = if (sensorSize != null && sensorSize.width > 0 && sensorSize.height > 0) {
        val diag = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        focal * FULL_FRAME_DIAGONAL_MM / diag
    } else {
        null
    }
    val mono = cam2.getCameraCharacteristic(
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
    ) == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
    // Logical multi-camera reach: the UW stop served through the wide logical
    // camera's zoom range (intrinsic ratio < 1), so the picker can skip a
    // duplicate UW pill. Unknown on most HALs — the eq-35mm ratio stays primary.
    val intrinsic = info.intrinsicZoomRatio.takeUnless {
        it == androidx.camera.core.CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN
    }
    val zoomFloor = info.zoomState.value?.minZoomRatio
    return RawLens(cam2.getCameraId(), facing, focal, eq35, mono, intrinsic, zoomFloor)
}

private data class RawLens(
    val id: String,
    val facing: LensFacing,
    val focalMm: Float,
    val eq35Mm: Float?,
    val mono: Boolean,
    val intrinsicZoomRatio: Float?,
    val minZoomRatio: Float?,
)

/** Classifies one facing family into [PhysicalLens] entries with fallback priorities. */
private fun buildFamily(facing: LensFacing, raws: List<RawLens>): List<PhysicalLens> {
    val family = raws.filter { it.facing == facing }.sortedBy { it.focalMm }
    if (family.isEmpty()) return emptyList()
    val nonMono = family.filterNot { it.mono }
    // Wide reference: 35mm-eq closest to ~27mm; fall back to the median focal length.
    val reference = nonMono.minByOrNull {
        it.eq35Mm?.let { eq -> kotlin.math.abs(eq - WIDE_REFERENCE_EQ_MM) } ?: Float.MAX_VALUE
    }
        ?: nonMono.getOrNull(nonMono.size / 2)
        ?: family.first()
    val classified = family.map { raw -> classifyLens(raw, reference, facing) }
    // Logical multi-camera dedup: when the wide logical camera already
    // reaches a UW stop via zoom-out (min zoom at/below its factor),
    // a separate UW pill would duplicate the zoom bar's sub-1x range —
    // drop it (issue #876).
    val wideFloor = classified.firstOrNull {
        it.lensType == LensType.WIDE || it.lensType == LensType.FRONT
    }?.let { ref -> family.firstOrNull { it.id == ref.logicalCameraId }?.minZoomRatio }
    return LensLabels.dropCoveredUltrawides(classified, wideFloor)
}

/** Classifies one raw lens against the family reference into a [PhysicalLens]. */
private fun classifyLens(raw: RawLens, reference: RawLens, facing: LensFacing): PhysicalLens {
    val type = classifyType(raw, reference, facing)
    return PhysicalLens(
        logicalCameraId = raw.id,
        lensType = type,
        facing = facing,
        focalLengthMm35Eq = raw.eq35Mm,
        nominalZoomRatio = raw.focalMm / reference.focalMm,
        intrinsicZoomRatio = raw.intrinsicZoomRatio,
        fallbackPriority = priorityOf(type),
        labelKey = labelOf(type),
    )
}

/** Lens type from the family reference geometry (mono wins over the focal heuristic). */
private fun classifyType(raw: RawLens, reference: RawLens, facing: LensFacing): LensType {
    if (raw.mono) return LensType.MONOCHROME
    if (raw == reference) return if (facing == LensFacing.FRONT) LensType.FRONT else LensType.WIDE
    if (raw.focalMm < reference.focalMm * ULTRA_WIDE_RATIO) return LensType.ULTRA_WIDE
    if (raw.focalMm > reference.focalMm * TELEPHOTO_RATIO) return LensType.TELEPHOTO
    return LensType.WIDE
}

/** Bind-attempt order for a lens type (wide/front first). */
private fun priorityOf(type: LensType): Int {
    return when (type) {
        LensType.WIDE, LensType.FRONT -> PRIORITY_WIDE
        LensType.ULTRA_WIDE -> PRIORITY_ULTRA_WIDE
        LensType.TELEPHOTO -> PRIORITY_TELEPHOTO
        LensType.MONOCHROME -> PRIORITY_MONO
    }
}

private const val PRIORITY_WIDE = 0
private const val PRIORITY_ULTRA_WIDE = 1
private const val PRIORITY_TELEPHOTO = 2
private const val PRIORITY_MONO = 3

/** Short UI key for a lens type. */
private fun labelOf(type: LensType): String {
    return when (type) {
        LensType.ULTRA_WIDE -> "ultrawide"
        LensType.WIDE -> "wide"
        LensType.TELEPHOTO -> "tele"
        LensType.MONOCHROME -> "mono"
        LensType.FRONT -> "front"
    }
}

/**
 * Fills [availableLensesMutable][com.vayunmathur.camera.util.CameraViewModel] once per
 * process and anchors the default selection (same-facing wide/front). No-op once
 * populated; call at the top of every session setup after obtaining the provider.
 */
fun CameraViewModel.ensureLensesEnumerated(provider: ProcessCameraProvider) {
    if (availableLensesMutable.value.isNotEmpty()) return
    val all = try {
        enumerateLenses(provider)
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Lens enumeration failed; sessions fall back to facing-only selectors", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Lens enumeration failed; sessions fall back to facing-only selectors", e)
        emptyList()
    }
    availableLensesMutable.value = all
    if (selectedLensMutable.value == null) {
        val facing = lensFacingMutable.value.toLensFacing() ?: LensFacing.BACK
        selectedLensMutable.value = LensSelectionLogic.filterByFacing(all, facing)
            .minByOrNull { it.fallbackPriority }
    }
    Log.dev("LensSelector", "Enumerated ${all.size} lenses; selected=${selectedLensMutable.value?.labelKey}")
}

/** Lenses of the current facing, ordered by fallback priority. */
fun CameraViewModel.currentLensFamily(): List<PhysicalLens> {
    val facing = lensFacingMutable.value.toLensFacing() ?: LensFacing.BACK
    return LensSelectionLogic.filterByFacing(availableLensesMutable.value, facing)
}

/**
 * Builds the selector for [facingInt], pinned to [lens] via a `CameraFilter` on
 * the logical camera ID when non-null. A null lens keeps the legacy facing-only
 * behavior.
 */
@Suppress("DEPRECATION")
fun CameraViewModel.lensSelector(facingInt: Int, lens: PhysicalLens?): CameraSelector {
    val builder = CameraSelector.Builder().requireLensFacing(facingInt)
    if (lens != null) {
        val id = lens.logicalCameraId
        builder.addCameraFilter { infos ->
            infos.filter { info ->
                try {
                    Camera2CameraInfo.from(info).getCameraId() == id
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
    return builder.build()
}

/**
 * Binds via [bind], retrying down the lens ladder: requested lens → same-facing
 * wide → any same-facing (priority order). When enumeration produced nothing,
 * the ladder is a single facing-only selector — identical to the legacy path.
 *
 * @return the lens that actually bound (null when facing-only) plus the camera.
 */
fun CameraViewModel.bindWithFallback(
    provider: ProcessCameraProvider,
    requested: PhysicalLens?,
    family: List<PhysicalLens>,
    bind: (CameraSelector) -> Camera,
): Pair<PhysicalLens?, Camera> {
    val facingInt = lensFacingMutable.value
    val ordered = buildList {
        if (requested != null) add(requested)
        family.sortedBy { it.fallbackPriority }.forEach { if (it != requested) add(it) }
        if (requested == null && family.isEmpty()) add(null)
    }
    var lastError: Exception? = null
    var first = true
    for (candidate in ordered) {
        try {
            // The caller unbinds before the first attempt; unbind between retries only.
            if (!first) {
                try {
                    provider.unbindAll()
                } catch (_: Exception) {
                }
            }
            first = false
            val camera = bind(lensSelector(facingInt, candidate))
            if (candidate != requested) {
                Log.status(
                    "LensSelector",
                    "Fell back to lens=${candidate?.labelKey} (requested=${requested?.labelKey})",
                )
            }
            return candidate to camera
        } catch (e: IllegalStateException) {
            lastError = e
            Log.status("LensSelector", "Bind failed on lens=${candidate?.labelKey}; trying next", e)
        } catch (e: IllegalArgumentException) {
            lastError = e
            Log.status("LensSelector", "Bind failed on lens=${candidate?.labelKey}; trying next", e)
        }
    }
    throw lastError ?: IllegalStateException("Lens bind failed with an empty ladder")
}
