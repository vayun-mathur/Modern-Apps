package com.vayunmathur.camera.platform

import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.data.setAudioCodec
import com.vayunmathur.camera.data.setAudioInputSource
import com.vayunmathur.camera.data.setMirrorFront
import com.vayunmathur.camera.data.setVideoCodec
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Mid-recording rebind protection: bind-time settings (video/audio codec, audio
 * source, mirror) changed while recording used to rebind the session, whose
 * teardown stopped the active recording with no warning. They now persist as
 * desired values, stay out of the applied bind snapshot until the recording
 * stops, and mode/lens/flip switches are gated for the duration.
 */
@RunWith(AndroidJUnit4::class)
class RecordingRebindDeferralTest {

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun bindSettingsTrackDesiredWhenNotRecording() {
        val v = vm()
        idle()
        v.setVideoCodec(VideoCodec.HEVC)
        v.setAudioCodec(AudioCodec.OPUS)
        v.setAudioInputSource(AudioInputSource.MIC)
        v.setMirrorFront(false)
        idle()
        assertEquals(
            BindTimeSettings(VideoCodec.HEVC, AudioCodec.OPUS, AudioInputSource.MIC, false),
            v.bindTimeSettings.value,
        )
        assertFalse(v.deferredBindSettingsPending.value)
    }

    @Test
    fun settingsChangedMidRecordingAreDeferredThenAppliedAtStop() {
        val v = vm()
        idle()
        val boundBefore = v.bindTimeSettings.value

        v.startRecordingTimer()
        assertTrue(v.isRecording.value)

        // The Settings-page setters: desired + persisted change immediately...
        v.setVideoCodec(if (boundBefore.videoCodec == VideoCodec.AVC) VideoCodec.HEVC else VideoCodec.AVC)
        v.setAudioCodec(if (boundBefore.audioCodec == AudioCodec.AAC) AudioCodec.OPUS else AudioCodec.AAC)
        v.setAudioInputSource(
            if (boundBefore.audioInputSource == AudioInputSource.CAMCORDER) {
                AudioInputSource.MIC
            } else {
                AudioInputSource.CAMCORDER
            }
        )
        v.setMirrorFront(!boundBefore.mirrorFront)
        idle()

        // ...but the applied bind snapshot — the session-effect key — is held,
        // so no rebind fires and the recording is untouched.
        assertEquals(boundBefore, v.bindTimeSettings.value)
        assertTrue(v.deferredBindSettingsPending.value)
        assertTrue(v.isRecording.value)
        assertTrue(v.videoCodec.value != boundBefore.videoCodec)
        assertTrue(v.audioCodec.value != boundBefore.audioCodec)
        assertTrue(v.audioInputSource.value != boundBefore.audioInputSource)
        assertEquals(!boundBefore.mirrorFront, v.mirrorFront.value)

        // At stop the snapshot releases: the next bind picks the new values up.
        v.stopRecordingTimer()
        idle()
        assertFalse(v.isRecording.value)
        assertFalse(v.deferredBindSettingsPending.value)
        assertEquals(
            BindTimeSettings(v.videoCodec.value, v.audioCodec.value, v.audioInputSource.value, v.mirrorFront.value),
            v.bindTimeSettings.value,
        )
    }

    @Test
    fun recordingSurvivesAllBindSettingsChangingAtOnce() {
        val v = vm()
        idle()
        v.startRecordingTimer()
        val durationAtStart = v.recordingDurationSec.value
        repeat(2) {
            v.setVideoCodec(VideoCodec.AV1)
            v.setAudioCodec(AudioCodec.OPUS)
            v.setAudioInputSource(AudioInputSource.UNPROCESSED)
            v.setMirrorFront(false)
            idle()
            assertTrue(v.isRecording.value, "recording must survive settings change #$it")
            assertTrue(v.deferredBindSettingsPending.value)
        }
        assertEquals(durationAtStart, v.recordingDurationSec.value)
        v.stopRecordingTimer()
        idle()
        assertEquals(AudioInputSource.UNPROCESSED, v.bindTimeSettings.value.audioInputSource)
    }

    @Test
    fun modeLensAndFlipSwitchesAreGatedWhileRecording() {
        val v = vm()
        idle()
        val back = PhysicalLens("b", LensType.WIDE, LensFacing.BACK, 24f, 1f, null, 0, "wide")
        val tele = PhysicalLens("t", LensType.TELEPHOTO, LensFacing.BACK, 70f, 3f, null, 1, "tele")
        v.availableLensesMutable.value = listOf(back, tele)
        v.selectLens(back)
        assertEquals(back, v.selectedLens.value)
        v.switchCameraMode(CameraMode.VIDEO)
        assertEquals(CameraMode.VIDEO, v.cameraMode.value)

        v.startRecordingTimer()
        // None of the rebind triggers may move while a take is running.
        v.switchCameraMode(CameraMode.PHOTO)
        assertEquals(CameraMode.VIDEO, v.cameraMode.value)
        v.selectLens(tele)
        assertEquals(back, v.selectedLens.value)
        v.flipCamera()
        assertEquals(CameraSelector.LENS_FACING_BACK, v.lensFacing.value)
        assertTrue(v.isRecording.value)

        // After the stop they all work again.
        v.stopRecordingTimer()
        v.selectLens(tele)
        assertEquals(tele, v.selectedLens.value)
        v.switchCameraMode(CameraMode.PHOTO)
        assertEquals(CameraMode.PHOTO, v.cameraMode.value)
        v.flipCamera()
        assertEquals(CameraSelector.LENS_FACING_FRONT, v.lensFacing.value)
    }
}
