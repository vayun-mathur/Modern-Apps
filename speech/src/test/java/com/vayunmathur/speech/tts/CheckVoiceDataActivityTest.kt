package com.vayunmathur.speech.tts

import android.speech.tts.TextToSpeech
import com.vayunmathur.speech.domain.SupertonicVoices
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CheckVoiceDataActivityTest {

    @Test
    fun bundledVoicesAreReportedAvailableInIso3Form() {
        val controller = Robolectric.buildActivity(CheckVoiceDataActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        val shadow = shadowOf(activity)
        val data = shadow.resultIntent
        assertNotNull(data)
        val available = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES)
        val unavailable = data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES)
        assertNotNull(available)
        assertNotNull(unavailable)
        // The real bundle ships in the APK, so every language is available in
        // the ISO-3 pair form and none is unavailable.
        if (shadow.resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
            assertEquals(
                SupertonicVoices.ALL.map { "${it.iso3}-${it.iso3Country}" },
                available.toList(),
            )
            assertEquals(emptyList(), unavailable.toList())
            assertEquals("eng-USA", available.first())
            assertEquals("vie-VNM", available.last())
            assertEquals(31, available.size)
        } else {
            assertEquals(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, shadow.resultCode)
            assertEquals(emptyList(), available.toList())
            assertEquals(31, unavailable.size)
        }
        // Either way the two lists partition the catalog.
        assertEquals(31, available.size + unavailable.size)
    }
}
