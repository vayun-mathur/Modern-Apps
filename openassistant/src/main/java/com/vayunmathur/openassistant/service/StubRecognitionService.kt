package com.vayunmathur.openassistant.service

import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Minimal no-op recognition service. It exists solely because the
 * `recognitionService` attribute in @xml/voice_interaction_service is REQUIRED by
 * the platform for a voice interaction service — OpenAssistant does not perform
 * on-device ASR here, so every listen request is immediately failed.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: android.os.RemoteException) {
        }
    }

    override fun onStopListening(listener: Callback?) {}

    override fun onCancel(listener: Callback?) {}
}
