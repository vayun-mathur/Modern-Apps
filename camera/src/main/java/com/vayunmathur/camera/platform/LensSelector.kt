package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import com.vayunmathur.library.log.Log
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensLabels
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.platform.CameraViewModel
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Maps the pure [LensFacing] to the platform lens-facing constant (CameraMetadata values). */
fun LensFacing.toSelectorInt(): Int = when (this) {
    LensFacing.BACK -> CameraMetadata.LENS_FACING_BACK
    LensFacing.FRONT -> CameraMetadata.LENS_FACING_FRONT
}

/** Maps a platform lens-facing constant to [LensFacing]; null for external/unknown. */
fun Int.toLensFacing(): LensFacing? = when (this) {
    CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
    CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
    else -> null
}

/**
 * Enumerates every physical/logical lens from `CameraManager.cameraIdList`
 * + `CameraCharacteristics` (`LENS_FACING`, focal lengths, sensor physical
 * size for the 35mm-equivalent label, mono color-filter detection).
 *
 * Types are assigned per facing family sorted by focal length: the lens whose
 * 35mm-equivalent is closest to ~27mm is the wide reference (1x); shorter is
 * ultra-wide, longer is telephoto; a MONO color-filter arrangement wins over the
 * focal heuristic. Front-facing families mark their reference lens FRONT.
 *
 * Safe to call off the main thread; never throws (returns empty on failure).
 */
fun enumerateLenses(manager: CameraManager): List<PhysicalLens> {
    val raws = manager.cameraIdList.mapNotNull { id -> readRawLens(manager, id) }

    // Logical multi-camera (e.g. a UW stop reachable via the wide camera's
    // zoom range) plus same-physical-lens duplicates would each render an
    // extra identical pill — `buildFamily` handles the former per facing,
    // and the dedupe collapses the latter (issue #876).
    return LensLabels.dedupe(
        LensFacing.entries.flatMap { facing -> buildFamily(facing, raws) }
    )
}

/** Full-frame diagonal (mm) of 35mm film; wide-reference eq focal length + family ratios. */
/** Value CameraX returns from `CameraInfo.getIntrinsicZoomRatio()` when unknown. */
private const val INTRINSIC_ZOOM_RATIO_UNKNOWN_SENTINEL = 1.0f

private const val FULL_FRAME_DIAGONAL_MM = 43.27f
private const val WIDE_REFERENCE_EQ_MM = 27f
private const val ULTRA_WIDE_RATIO = 0.85f
private const val TELEPHOTO_RATIO = 1.4f

/** Reads one camera id's physical-lens description; null when unusable. */
private fun readRawLens(manager: CameraManager, cameraId: String): RawLens? {
    return try {
        readRawLensOrThrow(manager, cameraId)
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Skipping camera during lens enumeration", e)
        null
    } catch (e: android.hardware.camera2.CameraAccessException) {
        Log.status("LensSelector", "Skipping camera during lens enumeration", e)
        null
    }
}

/** Physical-lens description probed off one camera id (null for external/unknown lenses). */
private fun readRawLensOrThrow(manager: CameraManager, cameraId: String): RawLens? {
    val chars = manager.getCameraCharacteristics(cameraId)
    val facing = when (
        chars.get(CameraCharacteristics.LENS_FACING)
    ) {
        CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
        CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
        // External / unknown: not a phone lens.
        else -> return null
    }
    val focal = chars.get(
        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
    )?.firstOrNull() ?: return null
    val sensorSize = chars.get(
        CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
    )
    val eq35 = if (sensorSize != null && sensorSize.width > 0 && sensorSize.height > 0) {
        val diag = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
        focal * FULL_FRAME_DIAGONAL_MM / diag
    } else {
        null
    }
    val mono = chars.get(
        CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
    ) == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
    // CameraX's intrinsic zoom ratio has no Camera2 equivalent; the
    // eq-35mm ratio stays primary and the field stays null (unknown).
    val intrinsic: Float? = null
    val zoomFloor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower
    } else {
        null
    }
    return RawLens(cameraId, facing, focal, eq35, mono, intrinsic, zoomFloor)
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
 * Fills [availableLensesMutable][com.vayunmathur.camera.platform.CameraViewModel] once per
 * process and anchors the default selection (same-facing wide/front). No-op once
 * populated; call at the top of every session setup after obtaining the provider.
 */
fun CameraViewModel.ensureLensesEnumerated() {
    if (availableLensesMutable.value.isNotEmpty()) return
    val manager = app.getSystemService(CameraManager::class.java) ?: return
    val all = try {
        enumerateLenses(manager)
    } catch (e: IllegalStateException) {
        Log.status("LensSelector", "Lens enumeration failed; sessions fall back to facing-only selectors", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.status("LensSelector", "Lens enumeration failed; sessions fall back to facing-only selectors", e)
        emptyList()
    }
    availableLensesMutable.value = all
    // Reconcile the startup seed (persisted lens, seeded synchronously at
    // ViewModel init so the Camera2 preview could bind before this probe
    // ran) with the enumerated truth: swap the placeholder for the real
    // lens with the same id, or fall back to the facing default. The
    // Camera2 preview keys its session on facing only, so this swap never
    // re-opens the camera — it only corrects the lens identity.
    val facing = lensFacingMutable.value.toLensFacing() ?: LensFacing.BACK
    val seeded = selectedLensMutable.value
    val canonical = all.firstOrNull {
        it.logicalCameraId == seeded?.logicalCameraId && it.facing == seeded?.facing
    } ?: LensSelectionLogic.filterByFacing(all, facing).minByOrNull { it.fallbackPriority }
    if (canonical != null && canonical != seeded) {
        selectedLensMutable.value = canonical
    }
    selectedLensMutable.value?.let { persistStartupLens(it) }
    Log.dev("LensSelector", "Enumerated ${all.size} lenses; selected=${selectedLensMutable.value?.labelKey}")
}

/** DataStore keys for the persisted last-used lens (startup fast path). */
internal const val LAST_LENS_FACING_KEY = "camera_last_lens_facing"
internal const val LAST_LENS_ID_KEY = "camera_last_lens_id"
/** Placeholder id before any lens has ever been persisted/enumerated. */
internal const val STARTUP_UNRESOLVED_LENS_ID = "startup-unresolved"

/**
 * Seeds [selectedLensMutable] synchronously from the persisted last lens
 * (DataStore sync mirror read, same pattern as `loadSettings`) so
 * `CameraScreenState.useCamera2Preview` is satisfiable on the very first
 * composition — the Camera2 preview resolves the openable id itself from
 * the facing via `CameraManager.cameraIdList` and never opens this id.
 * The placeholder carries only facing + identity; background enumeration
 * ([ensureLensesEnumerated]) reconciles it with the real lens. With no
 * persisted lens the seed is the BACK wide placeholder, matching the
 * historical default facing.
 */
fun CameraViewModel.seedStartupLens() {
    if (selectedLensMutable.value != null) return
    val facing = try {
        LensFacing.valueOf(ds.getString(LAST_LENS_FACING_KEY) ?: "")
    } catch (e: IllegalArgumentException) {
        LensFacing.BACK
    }
    val id = ds.getString(LAST_LENS_ID_KEY)?.takeIf { it.isNotBlank() }
        ?: STARTUP_UNRESOLVED_LENS_ID
    lensFacingMutable.value = facing.toSelectorInt()
    selectedLensMutable.value = PhysicalLens(
        logicalCameraId = id,
        lensType = if (facing == LensFacing.FRONT) LensType.FRONT else LensType.WIDE,
        facing = facing,
        focalLengthMm35Eq = null,
        nominalZoomRatio = 1f,
        fallbackPriority = 0,
        labelKey = if (facing == LensFacing.FRONT) "front" else "wide",
    )
}

/** Persists [lens] as the startup fast-path lens for the next launch. */
fun CameraViewModel.persistStartupLens(lens: PhysicalLens) {
    viewModelScope.launch {
        ds.setString(LAST_LENS_FACING_KEY, lens.facing.name)
        ds.setString(LAST_LENS_ID_KEY, lens.logicalCameraId)
    }
}

/** Lenses of the current facing, ordered by fallback priority. */
fun CameraViewModel.currentLensFamily(): List<PhysicalLens> {
    val facing = lensFacingMutable.value.toLensFacing() ?: LensFacing.BACK
    return LensSelectionLogic.filterByFacing(availableLensesMutable.value, facing)
}
