package com.vayunmathur.maps.ipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The missing-app signal for cross-app intent launches.
 * Slice-2 coverage for maps (basename I, outside the slice-1 dirs).
 *
 * [launchIntent] itself needs a live `MainActivity.intentLauncher`, so it is
 * only exercisable on-device; the exception it raises on a missing app is
 * pure JVM and pinned here.
 */
class IntentLaunchSlice2Test {

    @Test
    fun `the exception names the missing package`() {
        val e = MissingAppException("com.vayunmathur.taxi")
        assertEquals("com.vayunmathur.taxi", e.packageName)
    }

    @Test
    fun `the message tells the caller a result will never come`() {
        val e = MissingAppException("com.vayunmathur.taxi")
        assertTrue(
            e.message!!.contains("com.vayunmathur.taxi"),
            "message should name the package, was: ${e.message}",
        )
    }

    @Test
    fun `it is a plain exception callers can catch`() {
        val caught = try {
            throw MissingAppException("com.example.gone")
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (_: MissingAppException) {
            true
        }
        assertTrue(caught)
    }
}
