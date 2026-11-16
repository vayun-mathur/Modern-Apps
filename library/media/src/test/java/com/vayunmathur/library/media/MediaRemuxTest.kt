package com.vayunmathur.library.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Remux failure contract plus the resampler's Kaiser-window math.
 *
 * Under Robolectric the extractor finds no tracks in any input, which is
 * exactly the "no readable Opus track" case [OpusRemuxer.remux] must answer
 * with null (callers keep the original download bytes). The happy-path
 * stream copy needs a platform MediaMuxer and runs on device.
 */
@RunWith(RobolectricTestRunner::class)
class MediaRemuxTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun remuxTempFiles(): List<String> =
        context.cacheDir.listFiles()
            ?.filter { it.name.startsWith("mb-remux-") }
            ?.map { it.name }
            .orEmpty()

    @Test
    fun remuxOfGarbageReturnsNullAndCleansTempFiles() {
        val before = remuxTempFiles()
        assertNull(OpusRemuxer.remux(context, ByteArray(512) { 0x55 }))
        assertEquals(before, remuxTempFiles(), "temp files must be deleted on every path")
    }

    @Test
    fun remuxOfEmptyInputReturnsNull() {
        assertNull(OpusRemuxer.remux(context, ByteArray(0)))
    }

    @Test
    fun remuxOfFakeWebmHeaderReturnsNull() {
        // EBML magic + a plausible-looking but trackless body.
        val fake = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(256) { it.toByte() }
        assertNull(OpusRemuxer.remux(context, fake))
    }

    @Test
    fun kaiserBetaFollowsTheThreeRegimeFormula() {
        // The production filter always designs for 80 dB, so the 21..50 dB
        // and <21 dB regimes of Kaiser's formula are only reachable by
        // invoking the (private) design function directly. The values are
        // the published Kaiser estimates, not snapshots of the code.
        val companion = PolyphaseResampler::class.java.getDeclaredField("Companion")
            .apply { isAccessible = true }.get(null)
        val beta = companion.javaClass.getDeclaredMethod("kaiserBeta", Double::class.javaPrimitiveType)
            .apply { isAccessible = true }

        fun betaAt(db: Double): Double = beta.invoke(companion, db) as Double

        assertEquals(0.0, betaAt(10.0), 1e-12)
        assertEquals(0.0, betaAt(21.0), 1e-12)
        val mid = 0.5842 * (30.0 - 21.0).pow(0.4) + 0.07886 * (30.0 - 21.0)
        assertEquals(mid, betaAt(30.0), 1e-9)
        assertTrue(betaAt(50.0) > betaAt(30.0), "beta must grow with attenuation")
        assertEquals(0.1102 * (80.0 - 8.7), betaAt(80.0), 1e-9)
        // sinc(0) is the removable-singularity case.
        val sinc = companion.javaClass.getDeclaredMethod("sinc", Double::class.javaPrimitiveType)
            .apply { isAccessible = true }
        assertEquals(1.0, sinc.invoke(companion, 0.0) as Double, 1e-12)
        assertTrue(abs((sinc.invoke(companion, 0.5) as Double) - 2.0 / PI) < 1e-12)
    }
}
