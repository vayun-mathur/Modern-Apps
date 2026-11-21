package com.vayunmathur.camera.domain

import kotlin.math.roundToInt

/** How much the preview scene is moving, from the analysis stream (see [SceneMotionEstimator]). */
enum class SceneMotion { STATIC, MODERATE, HIGH_MOTION }

/**
 * One rung of the video bitrate ladder. This is the single source for the values the
 * Recorder is built with (UHD 48 / FHD 25 / HD 12 / SD 6 Mbps at 30 fps); the platform
 * layer maps CameraX qualities onto these tiers so the policy stays CameraX-free.
 */
enum class VideoQualityTier(val baseBitrateBps: Int, val width: Int, val height: Int) {
    UHD(48_000_000, 3840, 2160),
    FHD(25_000_000, 1920, 1080),
    HD(12_000_000, 1280, 720),
    SD(6_000_000, 720, 480);

    /** Next lower rung; SD has none and stays SD. */
    fun lower(): VideoQualityTier = when (this) {
        UHD -> FHD
        FHD -> HD
        HD -> SD
        SD -> SD
    }
}

/** Thermal pressure as the policy needs it; the platform maps PowerManager onto this. */
enum class ThermalPressure { NORMAL, SEVERE, UNKNOWN }

/** Why a decision was forced below the scene's natural bitrate. */
enum class AdaptiveReduction { LOW_STORAGE, THERMAL }

/**
 * Everything [AdaptiveVideoBitratePolicy] decides from, snapshotted before a recording.
 *
 * @property motion latest scene classification, or null when the estimator has no
 *   samples yet (recording pressed before the analysis stream warmed up). Null is
 *   treated as "no evidence to save bits": the rung is used as-is.
 * @property freeStorageBytes bytes free where the recording is staged, or null when
 *   the probe failed. Null never forces a reduction.
 * @property boostAllowed the encoder probe confirmed the boosted bitrate is inside
 *   the chosen codec's supported range at this tier's resolution/frame rate.
 */
data class AdaptiveBitrateInput(
    val baseTier: VideoQualityTier,
    val motion: SceneMotion?,
    val freeStorageBytes: Long?,
    val thermal: ThermalPressure,
    val boostAllowed: Boolean,
)

/**
 * The bitrate to build the Recorder with, plus why.
 *
 * @property effectiveTier rung after storage/thermal step-downs. Only the bitrate
 *   steps down; the recorded resolution (QualitySelector) never changes here.
 * @property reductions forced step-downs that were applied (empty for scene-only cuts).
 */
data class AdaptiveBitrateDecision(
    val bitrateBps: Int,
    val baseTier: VideoQualityTier,
    val effectiveTier: VideoQualityTier,
    val motion: SceneMotion?,
    val reductions: Set<AdaptiveReduction>,
    val boosted: Boolean,
) {
    /** True when storage/thermal actually lowered the bitrate below the base rung. */
    val forcedReduction: Boolean
        get() = reductions.isNotEmpty() && bitrateBps < baseTier.baseBitrateBps
}

/**
 * Pure adaptive-bitrate policy: scene motion scales the rung, storage/thermal step
 * the rung down, and a quality floor keeps any combination from starving the encoder.
 *
 * Deliberately has no notion of changing a bitrate mid-recording: CameraX fixes the
 * Recorder's target bitrate when the Recorder is built, so a decision is only ever
 * consumed before `prepareRecording`.
 */
object AdaptiveVideoBitratePolicy {
    /** Below this much free space the policy steps down one rung (~500 MB). */
    const val LOW_STORAGE_THRESHOLD_BYTES: Long = 500L * 1024 * 1024

    const val STATIC_FACTOR: Double = 0.55
    const val MODERATE_FACTOR: Double = 0.80
    const val HIGH_MOTION_FACTOR: Double = 1.0
    const val HIGH_MOTION_BOOST_FACTOR: Double = 1.25

    /** No decision may fall below this fraction of its effective rung. */
    const val QUALITY_FLOOR_FRACTION: Double = 0.40

    fun decide(input: AdaptiveBitrateInput): AdaptiveBitrateDecision {
        var tier = input.baseTier
        val reductions = linkedSetOf<AdaptiveReduction>()
        val free = input.freeStorageBytes
        if (free != null && free < LOW_STORAGE_THRESHOLD_BYTES) {
            tier = tier.lower()
            reductions += AdaptiveReduction.LOW_STORAGE
        }
        if (input.thermal == ThermalPressure.SEVERE) {
            tier = tier.lower()
            reductions += AdaptiveReduction.THERMAL
        }
        val steppedDown = tier != input.baseTier

        // A boost is a quality spend; never spend while storage/thermal forced a cut.
        val boosted = input.motion == SceneMotion.HIGH_MOTION && !steppedDown &&
            input.boostAllowed &&
            (input.baseTier == VideoQualityTier.UHD || input.baseTier == VideoQualityTier.FHD)
        val factor = when {
            boosted -> HIGH_MOTION_BOOST_FACTOR
            input.motion == SceneMotion.STATIC -> STATIC_FACTOR
            input.motion == SceneMotion.MODERATE -> MODERATE_FACTOR
            else -> HIGH_MOTION_FACTOR // HIGH_MOTION, or unknown: rung as-is
        }
        val floor = (tier.baseBitrateBps * QUALITY_FLOOR_FRACTION).roundToInt()
        val bitrate = maxOf((tier.baseBitrateBps * factor).roundToInt(), floor)
        return AdaptiveBitrateDecision(
            bitrateBps = bitrate,
            baseTier = input.baseTier,
            effectiveTier = tier,
            motion = input.motion,
            reductions = reductions,
            boosted = boosted,
        )
    }
}

/**
 * One analysis frame reduced to the two numbers the classifier needs, on the
 * 0-255 luminance scale.
 *
 * @property meanAbsDiff mean per-sample absolute difference against the previous frame.
 * @property meanGradient mean absolute horizontal neighbour difference within this
 *   frame (detail/texture). Detail alone is not motion: it only re-weights a real diff.
 */
data class FrameDiffSample(val meanAbsDiff: Double, val meanGradient: Double)

/** Frame-difference math over downsampled luminance grids. Pure and allocation-light. */
object SceneMotionMath {
    /** Diff at/above which (after detail weighting) a scene counts as high motion. */
    const val HIGH_MOTION_THRESHOLD: Double = 9.0

    /** Diff below which (after detail weighting) a scene counts as static. */
    const val STATIC_THRESHOLD: Double = 2.5

    /**
     * Reduces two same-sized luminance grids (row-major, [gridWidth] samples per row)
     * to a [FrameDiffSample]. Returns null for empty or mismatched grids.
     */
    fun sample(previous: FloatArray, current: FloatArray, gridWidth: Int): FrameDiffSample? {
        if (previous.size != current.size || current.isEmpty() || gridWidth <= 0) return null
        var diffSum = 0.0
        var gradientSum = 0.0
        var gradientCount = 0
        for (i in current.indices) {
            diffSum += kotlin.math.abs(current[i] - previous[i])
            if (i % gridWidth != 0) {
                gradientSum += kotlin.math.abs(current[i] - current[i - 1])
                gradientCount++
            }
        }
        val gradient = if (gradientCount > 0) gradientSum / gradientCount else 0.0
        return FrameDiffSample(meanAbsDiff = diffSum / current.size, meanGradient = gradient)
    }

    /**
     * Classifies a smoothed diff. The gradient re-weights by at most +/-15%: a detailed
     * static scene (high gradient, ~zero diff) stays static, while the same camera
     * movement over texture counts for slightly more than over a flat wall.
     */
    fun classify(smoothedDiff: Double, smoothedGradient: Double): SceneMotion {
        val detailWeight = 0.85 + 0.30 * (smoothedGradient / 64.0).coerceIn(0.0, 1.0)
        val effective = smoothedDiff * detailWeight
        return when {
            effective < STATIC_THRESHOLD -> SceneMotion.STATIC
            effective < HIGH_MOTION_THRESHOLD -> SceneMotion.MODERATE
            else -> SceneMotion.HIGH_MOTION
        }
    }
}

/**
 * Smooths recent [FrameDiffSample]s (simple mean over the window) and classifies the
 * scene. Stateful but pure: feed it synthetic samples to test the math end to end.
 */
class SceneMotionEstimator(private val windowSize: Int = DEFAULT_WINDOW_SIZE) {
    private val samples = ArrayDeque<FrameDiffSample>()

    /** Latest classification, or null before the first sample. */
    var lastClassification: SceneMotion? = null
        private set

    val sampleCount: Int get() = samples.size

    fun add(sample: FrameDiffSample): SceneMotion {
        samples.addLast(sample)
        while (samples.size > windowSize) samples.removeFirst()
        val meanDiff = samples.sumOf { it.meanAbsDiff } / samples.size
        val meanGradient = samples.sumOf { it.meanGradient } / samples.size
        return SceneMotionMath.classify(meanDiff, meanGradient).also { lastClassification = it }
    }

    /** Frame-grid convenience over [SceneMotionMath.sample]; null when no sample could be taken. */
    fun addFrame(previous: FloatArray, current: FloatArray, gridWidth: Int): SceneMotion? =
        SceneMotionMath.sample(previous, current, gridWidth)?.let { add(it) }

    fun reset() {
        samples.clear()
        lastClassification = null
    }

    companion object {
        /** ~2.4 s of samples at the analyzer's ~5 Hz cadence: long enough to ride out a pan blip. */
        const val DEFAULT_WINDOW_SIZE: Int = 12
    }
}
