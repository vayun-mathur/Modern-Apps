package com.vayunmathur.auto.ui

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [MusicCaptureGrant] — the process-wide media-projection grant
 * holder. Fail-closed (no stored grant means no capture); storing and
 * clearing round-trip.
 */
@RunWith(RobolectricTestRunner::class)
class MusicCaptureGrantTest {

    @AfterTest
    fun reset() {
        MusicCaptureGrant.clear()
    }

    @Test
    fun `fresh state holds no grant`() {
        MusicCaptureGrant.clear()
        assertFalse(MusicCaptureGrant.hasGrant())
    }

    @Test
    fun `storing a result arms the grant and clearing disarms it`() {
        val context: android.content.Context = ApplicationProvider.getApplicationContext()
        val data = Intent("com.vayunmathur.auto.test.CAPTURE_RESULT")
        MusicCaptureGrant.storeResult(android.app.Activity.RESULT_OK, data)
        assertTrue(MusicCaptureGrant.hasGrant())
        MusicCaptureGrant.clear()
        assertFalse(MusicCaptureGrant.hasGrant())
        // With no stored grant the token recreation is null, never a crash.
        assertTrue(MusicCaptureGrant.projection(context) == null)
    }
}
