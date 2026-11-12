package com.vayunmathur.communicate.telephony

import android.media.AudioManager
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice-4 coverage for the shared call ringer ([CallRinger]).
 *
 * Robolectric with the media/vibration shadows: asserts start is idempotent per call, stop always
 * releases and only for its own line, a second start after stop re-rings, and the ringer switch is
 * honoured (#856: the ringer used to vibrate even in silent mode). The pure decision table lives in
 * `RingPolicyTest`; this checks the platform side follows it.
 */
@RunWith(RobolectricTestRunner::class)
class CallRingerSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private val vibrator: Vibrator
        get() = context.getSystemService(Vibrator::class.java)!!

    private val audio: AudioManager
        get() = context.getSystemService(AudioManager::class.java)!!

    private fun request(key: String = "peer", line: RingLine = RingLine.InApp, waiting: Boolean = false) =
        RingRequest(line, key, number = null, callWaiting = waiting)

    @BeforeTest
    fun reset() {
        RingLine.entries.forEach(CallRinger::stop)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
    }

    @AfterTest
    fun tearDown() {
        RingLine.entries.forEach(CallRinger::stop)
    }

    @Test
    fun `start vibrates and stop cancels`() {
        CallRinger.start(context, request())
        assertTrue(shadowOf(vibrator).isVibrating)
        CallRinger.stop(RingLine.InApp)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `second start for the same call keeps the same loop`() {
        CallRinger.start(context, request())
        CallRinger.start(context, request())
        assertTrue(shadowOf(vibrator).isVibrating)
        CallRinger.stop(RingLine.InApp)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `stop without start is safe`() {
        CallRinger.stop(RingLine.InApp)
        CallRinger.stop(RingLine.InApp)
    }

    @Test
    fun `start after stop rings again`() {
        CallRinger.start(context, request())
        CallRinger.stop(RingLine.InApp)
        CallRinger.start(context, request())
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `another line's stop leaves the ring alone`() {
        CallRinger.start(context, request(line = RingLine.Sim))
        CallRinger.stop(RingLine.InApp)
        CallRinger.stop(RingLine.GoogleVoice)
        assertTrue(shadowOf(vibrator).isVibrating)
        CallRinger.stop(RingLine.Sim)
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `silent mode neither rings nor vibrates`() {
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        CallRinger.start(context, request())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `vibrate mode vibrates`() {
        audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        CallRinger.start(context, request())
        assertTrue(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `call waiting does not vibrate`() {
        CallRinger.start(context, request(waiting = true))
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `silence mutes and a repeat start for the same call stays muted`() {
        CallRinger.start(context, request())
        CallRinger.silence(RingLine.InApp)
        assertFalse(shadowOf(vibrator).isVibrating)
        CallRinger.start(context, request())
        assertFalse(shadowOf(vibrator).isVibrating)
    }

    @Test
    fun `a different call after silence rings`() {
        CallRinger.start(context, request("first"))
        CallRinger.silence(RingLine.InApp)
        CallRinger.start(context, request("second"))
        assertTrue(shadowOf(vibrator).isVibrating)
    }
}
