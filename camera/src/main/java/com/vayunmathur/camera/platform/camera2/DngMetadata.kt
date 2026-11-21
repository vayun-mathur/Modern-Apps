package com.vayunmathur.camera.platform.camera2

/**
 * Camera2 depth Phase 2: DNG sidecar metadata assembly — the pure half.
 *
 * A DNG is only honest when every sensor field `DngCreator` would embed
 * is actually known: black-level pattern, white level, CFA arrangement,
 * both colour/calibration/forward matrix pairs and their illuminants,
 * plus a RAW stream to write. [DngMetadataAssembler] assembles exactly
 * that from the facade snapshot and names every missing piece; the night
 * RAW path never writes a DNG when this returns null.
 *
 * Deliberate gap (reported, not papered over): even a complete
 * [DngSensorMetadata] is not a DNG. `android.hardware.camera2.DngCreator`
 * additionally requires the live `CameraCharacteristics` and the frame's
 * live `TotalCaptureResult` objects. The ring deliberately retains only
 * pure data ([RingFrameMeta] / [RawStillMetadata]) so sessions stay
 * fakeable in tests, so no DNG writer is wired in this phase and no
 * RAW photo-format setting is offered. Wiring one means retaining live
 * results beside RAW ring frames in the driver — a Phase-3 decision.
 */
data class DngSensorMetadata(
    val blackLevelPattern: List<Int>,
    val whiteLevel: Int,
    val colorFilterArrangement: Int,
    val colorTransform1: List<Float>,
    val colorTransform2: List<Float>,
    val calibrationTransform1: List<Float>,
    val calibrationTransform2: List<Float>,
    val forwardMatrix1: List<Float>,
    val forwardMatrix2: List<Float>,
    val referenceIlluminant1: Int,
    val referenceIlluminant2: Int?,
    val activeArrayWidth: Int,
    val activeArrayHeight: Int,
)

object DngMetadataAssembler {

    /** Why a DNG cannot be written for [snapshot], empty when it can (metadata-wise). */
    fun missingRequirements(snapshot: LensCharacteristicsSnapshot): List<String> = buildList {
        if (!snapshot.supportsRaw) add("RAW capability not reported")
        if (snapshot.rawRingFormat == null) add("no RAW_SENSOR/RAW10 stream size")
        if (snapshot.rawBlackLevelPattern.size != 4) add("SENSOR_BLACK_LEVEL_PATTERN missing or not 4 values")
        val white = snapshot.rawWhiteLevel
        if (white == null || white <= 0) add("SENSOR_INFO_WHITE_LEVEL missing")
        if (snapshot.colorFilterArrangement == null) add("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT missing")
        if (snapshot.rawColorTransform1.size != 9) add("SENSOR_COLOR_TRANSFORM1 missing")
        if (snapshot.rawColorTransform2.size != 9) add("SENSOR_COLOR_TRANSFORM2 missing")
        if (snapshot.rawCalibrationTransform1.size != 9) add("SENSOR_CALIBRATION_TRANSFORM1 missing")
        if (snapshot.rawCalibrationTransform2.size != 9) add("SENSOR_CALIBRATION_TRANSFORM2 missing")
        if (snapshot.rawForwardMatrix1.size != 9) add("SENSOR_FORWARD_MATRIX1 missing")
        if (snapshot.rawForwardMatrix2.size != 9) add("SENSOR_FORWARD_MATRIX2 missing")
        if (snapshot.rawReferenceIlluminant1 == null) add("SENSOR_REFERENCE_ILLUMINANT1 missing")
        if (snapshot.activeArrayWidth <= 0 || snapshot.activeArrayHeight <= 0) {
            add("SENSOR_INFO_ACTIVE_ARRAY_SIZE missing")
        }
        // Illuminant 2 / second matrices are only meaningful as a pair:
        // a present transform2 with no illuminant2 is a half-known DNG.
        if (snapshot.rawColorTransform2.size == 9 && snapshot.rawReferenceIlluminant2 == null) {
            add("SENSOR_REFERENCE_ILLUMINANT2 missing for second colour transform")
        }
    }

    /** Assembled metadata, or null when [missingRequirements] is non-empty. */
    fun assemble(snapshot: LensCharacteristicsSnapshot): DngSensorMetadata? {
        if (missingRequirements(snapshot).isNotEmpty()) return null
        return DngSensorMetadata(
            blackLevelPattern = snapshot.rawBlackLevelPattern,
            whiteLevel = snapshot.rawWhiteLevel!!,
            colorFilterArrangement = snapshot.colorFilterArrangement!!,
            colorTransform1 = snapshot.rawColorTransform1,
            colorTransform2 = snapshot.rawColorTransform2,
            calibrationTransform1 = snapshot.rawCalibrationTransform1,
            calibrationTransform2 = snapshot.rawCalibrationTransform2,
            forwardMatrix1 = snapshot.rawForwardMatrix1,
            forwardMatrix2 = snapshot.rawForwardMatrix2,
            referenceIlluminant1 = snapshot.rawReferenceIlluminant1!!,
            referenceIlluminant2 = snapshot.rawReferenceIlluminant2,
            activeArrayWidth = snapshot.activeArrayWidth,
            activeArrayHeight = snapshot.activeArrayHeight,
        )
    }
}
