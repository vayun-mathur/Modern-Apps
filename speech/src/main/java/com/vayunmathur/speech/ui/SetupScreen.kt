package com.vayunmathur.speech.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.speech.domain.SupertonicVoices
import com.vayunmathur.speech.platform.SupertonicBundle
import com.vayunmathur.speech.platform.WhisperModel
import com.vayunmathur.speech.service.WhisperRecognitionService

/** Reads the device state and binds it to the stateless [SpeechSetupScreen]. */
@Composable
internal fun SetupScreen() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    // Recompute status whenever [refresh] changes (bumped when returning from settings).
    val isDefault = remember(refresh) {
        val current = Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        val mine = ComponentName(context, WhisperRecognitionService::class.java).flattenToString()
        current != null && ComponentName.unflattenFromString(current) ==
            ComponentName.unflattenFromString(mine)
    }
    val hasMic = remember(refresh) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    // Whisper
    val modelReady = remember(refresh) { WhisperModel.isReady(context) }

    // Every language the bundle covers. Not a per-install fact any more: the four networks and the
    // ten voices ship in the APK, so this list is the same on every device that has the asset.
    val ttsVoices = remember {
        SupertonicVoices.ALL.map { language ->
            TtsVoiceUiState(
                code = language.code,
                bcp47 = language.bcp47,
                nativeName = language.nativeName,
                englishName = language.englishName,
            )
        }
    }
    val ttsModelReady = remember(refresh) { SupertonicBundle.isPresent(context) }
    val isTtsDefault = remember(refresh) {
        Settings.Secure.getString(context.contentResolver, "tts_default_synth") == context.packageName
    }

    val launchMicRequest = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO
    ) { refresh++ }

    // Step 1 is settled by the gate above: by the time SetupScreen composes the
    // microphone is granted, so the card always renders done and the button is
    // gone. The step stays visible as a record, not an action.
    SpeechSetupScreen(
        state = SpeechSetupUiState(
            modelReady = modelReady,
            hasMic = hasMic,
            isRecognizerDefault = isDefault,
            ttsModelReady = ttsModelReady,
            isTtsDefault = isTtsDefault,
            ttsVoices = ttsVoices,
            currentTestLang = SupertonicVoices.DEFAULT.code,
        ),
        actions = object : SpeechSetupActions {
            override fun requestMicPermission() = launchMicRequest()

            override fun openVoiceInputSettings() {
                val localeIntent = Intent(Settings.ACTION_LOCALE_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val voiceInputIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val candidates = listOf(
                    voiceInputIntent,
                    localeIntent,
                    Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
                var launched = false
                for (i in candidates) {
                    if (runCatching { context.startActivity(i) }.isSuccess) {
                        launched = true
                        break
                    }
                }
                if (!launched) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }
                refresh++
            }

            override fun openTtsSettings() {
                runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                refresh++
            }

            override fun refresh() { refresh++ }
        },
    )
}

