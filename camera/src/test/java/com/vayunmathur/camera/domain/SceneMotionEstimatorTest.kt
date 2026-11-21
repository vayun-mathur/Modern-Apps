package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SceneMotionEstimatorTest {

    private val w = 8
    private val h = 6

    /** Flat grid at [value]. */
    private fun flat(value: Float) = FloatArray(w * h) { value }

    /** Checkerboard of [a]/[b]: maximum detail, zero motion when repeated. */
    private fun checker(a: Float, b: Float) =
        FloatArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) a else b }

    @Test
    fun identicalFramesAreStaticEvenWhenHighlyDetailed() {
        val est = SceneMotionEstimator()
        // A repeated checkerboard has a huge gradient but zero diff: detail is not motion.
        repeat(4) {
            assertEquals(SceneMotion.STATIC, est.addFrame(checker(0f, 255f), checker(0f, 255f), w))
        }
    }

    @Test
    fun sampleMathOnFlatFrames() {
        val s = SceneMotionMath.sample(flat(100f), flat(110f), w)
        assertEquals(10.0, s!!.meanAbsDiff)
        assertEquals(0.0, s.meanGradient)
        assertNull(SceneMotionMath.sample(flat(0f), FloatArray(3), w))
        assertNull(SceneMotionMath.sample(flat(0f), flat(0f), 0))
    }

    @Test
    fun smallBrightnessDriftIsStatic() {
        val est = SceneMotionEstimator()
        repeat(3) { assertEquals(SceneMotion.STATIC, est.addFrame(flat(100f), flat(101f), w)) }
    }

    @Test
    fun moderateShiftIsModerate() {
        val est = SceneMotionEstimator()
        // +5 luminance everywhere on a flat scene: effective 5 x 0.85 = 4.25.
        repeat(3) { assertEquals(SceneMotion.MODERATE, est.addFrame(flat(100f), flat(105f), w)) }
    }

    @Test
    fun largeShiftIsHighMotion() {
        val est = SceneMotionEstimator()
        repeat(3) { assertEquals(SceneMotion.HIGH_MOTION, est.addFrame(flat(50f), flat(90f), w)) }
    }

    @Test
    fun detailWeightsTheSameDiffUpwards() {
        // Same mean diff (10) reads higher over texture than over a flat wall.
        val flatClass = SceneMotionMath.classify(10.0, 0.0)
        val texturedClass = SceneMotionMath.classify(10.0, 255.0)
        assertEquals(SceneMotion.MODERATE, flatClass) // 10 x 0.85 = 8.5
        assertEquals(SceneMotion.HIGH_MOTION, texturedClass) // 10 x 1.15 = 11.5
        // ...but detail with (near-)zero diff can never fake motion.
        assertEquals(SceneMotion.STATIC, SceneMotionMath.classify(0.0, 255.0))
        assertEquals(SceneMotion.STATIC, SceneMotionMath.classify(2.0, 255.0)) // 2.3 < 2.5
    }

    @Test
    fun windowSmoothsASingleFrameSpike() {
        val est = SceneMotionEstimator(windowSize = 4)
        repeat(3) { est.add(FrameDiffSample(0.0, 0.0)) }
        // One violent frame in four: mean diff 10 -> effective 8.5, not high motion.
        assertEquals(SceneMotion.MODERATE, est.add(FrameDiffSample(40.0, 0.0)))
        assertEquals(SceneMotion.MODERATE, est.lastClassification)
    }

    @Test
    fun sustainedMotionTakesOverTheWindow() {
        val est = SceneMotionEstimator(windowSize = 2)
        est.add(FrameDiffSample(0.0, 0.0))
        est.add(FrameDiffSample(0.0, 0.0))
        assertEquals(SceneMotion.STATIC, est.lastClassification)
        est.add(FrameDiffSample(30.0, 0.0))
        assertEquals(SceneMotion.HIGH_MOTION, est.add(FrameDiffSample(30.0, 0.0)))
    }

    @Test
    fun noSamplesMeansNoClassification() {
        val est = SceneMotionEstimator()
        assertNull(est.lastClassification)
        assertEquals(0, est.sampleCount)
        est.add(FrameDiffSample(0.0, 0.0))
        est.reset()
        assertNull(est.lastClassification)
        assertEquals(0, est.sampleCount)
    }
}
