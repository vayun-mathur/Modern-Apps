package com.vayunmathur.speech.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.vayunmathur.speech.domain.SupertonicVoices
import com.vayunmathur.speech.platform.SupertonicBundle

/**
 * Answers the framework's `CHECK_TTS_DATA` probe: the system TTS settings run this before letting
 * the user pick our engine, to learn which voices are installed.
 *
 * The answer must be in the ISO-3 form — `eng-USA`, `deu-DEU` — and it must agree with
 * `SupertonicTtsService.onGetVoices`, which publishes the same pairs. If the two disagree, Settings'
 * Play button silently does nothing, with no error visible anywhere. That is why both sides build
 * the strings from [SupertonicVoices] rather than each spelling them out.
 *
 * # Why this is now a fixed answer
 *
 * With Piper this activity had real work to do: it migrated legacy voice directories, listed which
 * of 42 languages were extracted, and reported the rest as unavailable so Settings could offer them
 * as downloads. All 31 languages ship in the APK now, so either the bundle is there and every
 * language is available, or it is not and none is.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val all = ArrayList(SupertonicVoices.ALL.map { "${it.iso3}-${it.iso3Country}" })
        val present = SupertonicBundle.isPresent(this)
        val data = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                if (present) all else ArrayList(),
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                if (present) ArrayList() else all,
            )
        }
        setResult(
            if (present) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            data,
        )
        finish()
    }
}
