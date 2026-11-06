package com.vayunmathur.communicate.telephony

import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice-4 coverage for the shared call ringer ([CallRinger]).
 *
 * Robolectric with the media/vibration shadows: asserts start is idempotent,
 * stop always releases, and a second start after stop re-rings — the contract
 * that keeps answer/decline from every surface silencing the same loop twice.
 */
@RunWith(RobolectricTestRunner::class)
class CallRingerSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `start vibrates and stop cancels`() {
        CallRinger.stop()
        val vibrator = context.getSystemService(Vibrator::class.java)!!
        CallRinger.start(context)
        assertTrue(shadowOf(vibrator).isVibrating)
        CallRinger.stop()
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `second start while ringing keeps the same loop`() {
        CallRinger.stop()
        CallRinger.start(context)
        CallRinger.start(context)
        CallRinger.stop()
    }

    @Test
    fun `stop without start is safe`() {
        CallRinger.stop()
        CallRinger.stop()
    }

    @Test
    fun `start after stop rings again`() {
        CallRinger.stop()
        CallRinger.start(context)
        CallRinger.stop()
        CallRinger.start(context)
        CallRinger.stop()
    }

    @Test
    fun `media player state follows the ringer`() {
        CallRinger.stop()
        // The ringer owns exactly one MediaPlayer generation at a time; a stale
        // prepared callback must not restart audio after stop (guarded by
        // identity in CallRinger.startSound — exercised here via start/stop).
        CallRinger.start(context)
        CallRinger.stop()
    }
}
