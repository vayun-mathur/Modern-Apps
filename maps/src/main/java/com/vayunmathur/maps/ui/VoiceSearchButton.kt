package com.vayunmathur.maps.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.rememberPermissionRequest

/**
 * Mic button (P8) that launches the SYSTEM speech recognizer — the platform
 * [SpeechRecognizer], which the OS routes to the user-selected recognition
 * service (the MA speech app, fully offline, when installed). This is NOT an
 * in-app STT engine.
 *
 * On a final transcript it calls [onResult]; callers fill the search query and
 * run the P3 offline index search. RECORD_AUDIO is requested through the shared
 * [rememberPermissionRequest] helper (which deep-links to app settings on
 * permanent denial). When no recognition service is available the button hides
 * itself entirely (renders nothing).
 */
@Composable
fun VoiceSearchButton(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Hide the mic when the device has no recognition service to bind. Layoutlib has none
    // and `isRecognitionAvailable` throws there rather than returning false, so the
    // short-circuit has to come first for previews to render.
    if (LocalInspectionMode.current || !SpeechRecognizer.isRecognitionAvailable(context)) return

    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        onDispose { recognizer.destroy() }
    }

    fun startListening() {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(onResult)
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    val requestMic = rememberPermissionRequest(Manifest.permission.RECORD_AUDIO) { granted ->
        if (granted) startListening()
    }

    IconButton(onClick = requestMic, modifier = modifier) {
        IconMic()
    }
}
