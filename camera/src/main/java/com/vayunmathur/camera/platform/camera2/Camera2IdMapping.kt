package com.vayunmathur.camera.platform.camera2

import com.vayunmathur.camera.domain.LensFacing
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One entry of `CameraManager.cameraIdList` with the `LENS_FACING` read from
 * that id's own characteristics. [facing] is null when the characteristics
 * could not be read; such an entry can never be selected.
 */
data class CameraIdFacing(
    val cameraId: String,
    val facing: LensFacing?,
)

/** Outcome of resolving a selected lens to a Camera2-openable camera id. */
sealed interface Camera2IdResolution {
    /** A camera id taken verbatim from `CameraManager.cameraIdList`. */
    data class Resolved(val cameraId: String) : Camera2IdResolution

    /** No Camera2 id can serve the lens; [reason] is the error-path message. */
    data class Unavailable(val reason: String) : Camera2IdResolution
}

/**
 * Pure seam mapping a selected lens to the camera id the Camera2 stack may
 * open. The only ids ever handed to `CameraManager.getCameraCharacteristics`
 * / `openCamera` on the Camera2 preview path are ids from
 * `CameraManager.cameraIdList` (enumerated by
 * [CharacteristicsFacade.cameraIdFacings]); the selected lens contributes
 * only its [LensFacing], matched against each listed id's `LENS_FACING`.
 *
 * A CameraX-derived id (`PhysicalLens.logicalCameraId`, from
 * `Camera2CameraInfo.getCameraId()` over `availableCameraInfos`) is never an
 * input here: CameraX enumerates physical sub-cameras of a logical
 * multi-camera (and other infos) whose ids need not exist in
 * `cameraIdList` at all — on the crashing Pixel 9 Pro XL the selected lens
 * carried CameraX id "1", which `getCameraCharacteristics("1")` rejected
 * with `IllegalArgumentException: unknown device 1`.
 */
object Camera2IdResolver {

    /**
     * Resolves [facing] against [available] (the `cameraIdList` entries, in
     * list order). The first listed id whose `LENS_FACING` matches wins —
     * `cameraIdList` lists the logical camera of a facing before its
     * physical sub-cameras, so the first match is the openable logical id.
     * Unknown facing, a blank id, or no match is [Camera2IdResolution.Unavailable],
     * never an exception and never a guessed id.
     */
    fun resolve(facing: LensFacing?, available: List<CameraIdFacing>): Camera2IdResolution {
        if (facing == null) {
            return Camera2IdResolution.Unavailable(
                "no lens facing for Camera2 preview (available=${available.map { it.cameraId }})",
            )
        }
        val match = available.firstOrNull { entry ->
            entry.cameraId.isNotBlank() && entry.facing == facing
        }
        return if (match != null) {
            Camera2IdResolution.Resolved(match.cameraId)
        } else {
            Camera2IdResolution.Unavailable(
                "no Camera2 camera id for facing $facing in CameraManager.cameraIdList " +
                    "(available=${available.map { "${it.cameraId}:${it.facing}" }})",
            )
        }
    }
}

/**
 * One-shot error sink for one Camera2 preview bind: the first [report]
 * forwards to [sink] and returns true; every later report is dropped and
 * returns false. This breaks the onError -> recomposition -> onError cycle:
 * a session/init failure can be reported from any thread any number of
 * times, but the fallback latch in the ViewModel is signalled exactly once
 * per constructed session holder, and a latched failure never re-enters
 * the error path while the same holder is alive.
 */
class OnceErrorReporter(
    private val sink: (String) -> Unit,
) {
    private val reported = AtomicBoolean(false)

    /** True once an error has been forwarded to [sink]. */
    val hasReported: Boolean get() = reported.get()

    /** Forwards [message] on the first call only; returns whether it did. */
    fun report(message: String): Boolean {
        if (!reported.compareAndSet(false, true)) return false
        sink(message)
        return true
    }
}
