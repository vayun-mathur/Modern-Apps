package com.vayunmathur.speech.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.speech.R

/**
 * The setup screen, with no dependency on the device so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 *
 * The two test cards below build a SpeechRecognizer / TextToSpeech only when their button is
 * pressed, never during composition, so they render unchanged under Layoutlib.
 *
 * Keeps the `Column(.padding(padding).verticalScroll)` structure without a
 * TopAppBar per fix 91ecb8f5b — titles-only cards, via AppScaffold with an empty top bar.
 */
@Composable
fun SpeechSetupScreen(state: SpeechSetupUiState, actions: SpeechSetupActions) {
    AppScaffold(topBar = {}) { padding ->
        // Letterboxed on expanded windows so the setup cards keep a readable measure.
        // This screen deliberately has no top bar (titles-only cards).
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrokenInstallCard(state)
                MicStep(state, actions)
                RecognizerStep(state, actions)
                TestSection(enabled = state.hasMic)
                TtsStep(state, actions)
                TtsTestSection(
                    enabled = state.ttsModelReady,
                    languages = state.ttsVoices,
                    currentLang = state.currentTestLang,
                )
            }
        }
    }
}

@Composable
private fun BrokenInstallCard(state: SpeechSetupUiState) {
    // Only when something is actually wrong. Both models ship in the APK, so there is no step
    // for the user to complete here — either the assets are readable or the install is broken.
    // They were two permanently-ticked "steps" until the assets stopped being downloads.
    if (!state.modelReady || !state.ttsModelReady) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.broken_install_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.broken_install_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MicStep(state: SpeechSetupUiState, actions: SpeechSetupActions) {
    StepCard(
        index = 1,
        title = "Microphone access",
        done = state.hasMic,
    ) {
        if (!state.hasMic) {
            Button(onClick = actions::requestMicPermission) {
                Text(stringResource(R.string.grant_microphone))
            }
        }
    }
}

@Composable
private fun RecognizerStep(state: SpeechSetupUiState, actions: SpeechSetupActions) {
    StepCard(
        index = 2,
        title = "Set as speech recognizer",
        done = state.isRecognizerDefault,
    ) {
        OutlinedButton(onClick = actions::openVoiceInputSettings) {
            Text(stringResource(R.string.open_voice_input_settings))
        }
    }
}

@Composable
private fun TtsStep(state: SpeechSetupUiState, actions: SpeechSetupActions) {
    StepCard(
        index = 3,
        title = "Set as text-to-speech engine",
        done = state.isTtsDefault,
    ) {
        OutlinedButton(onClick = actions::openTtsSettings) {
            Text(stringResource(R.string.open_text_to_speech_settings))
        }
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Boolean,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "$index. $title" + if (done) "  ✓" else "",
                fontWeight = FontWeight.Bold,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            action()
        }
    }
}

@Composable
private fun TestSection(enabled: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_it), fontWeight = FontWeight.Bold)
            Button(
                enabled = enabled,
                onClick = {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        status = "No recognizer selected yet (finish step 2)."
                        return@Button
                    }
                    result = ""
                    status = "Listening…"
                    val sr = SpeechRecognizer.createSpeechRecognizer(context)
                    sr.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() { status = "Transcribing…" }
                        override fun onError(error: Int) { status = "Error ($error)"; sr.destroy() }
                        override fun onResults(results: Bundle?) {
                            result = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull().orEmpty()
                            status = ""
                            sr.destroy()
                        }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }
                    sr.startListening(intent)
                },
            ) { Text(stringResource(R.string.test_microphone)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            if (result.isNotBlank()) Text(stringResource(R.string.heard, result))
        }
    }
}
