package com.vayunmathur.speech.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconArrowDropDown
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.speech.R
import java.util.Locale



@Composable
internal fun TtsTestSection(
    enabled: Boolean,
    languages: List<TtsVoiceUiState>,
    currentLang: String,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("") }
    var selectedCode by remember(currentLang, languages) {
        mutableStateOf(
            if (languages.any { it.code == currentLang }) currentLang
            else languages.firstOrNull()?.code ?: "en"
        )
    }
    // Hold the engine across recompositions and release it when leaving the screen.
    val engine = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        onDispose { engine.value?.shutdown(); engine.value = null }
    }

    // One sample sentence per language the bundle covers, and the reason each is written out rather
    // than translated at runtime is that the model reads *characters*: a sentence has to exercise
    // the script, not just the language, or a missing codepoint in the table goes unnoticed.
    val samples = remember { ttsSamples() }

    TtsTestCard(
        enabled = enabled,
        languages = languages,
        selectedCode = selectedCode,
        onSelect = { selectedCode = it },
        status = status,
        onSpeak = {
            speakSample(
                context = context,
                engine = engine,
                languages = languages,
                selectedCode = selectedCode,
                samples = samples,
                onStatus = { status = it },
            )
        },
    )
}

private fun ttsSamples(): Map<String, String> = mapOf(
    "en" to "Hello, this voice runs entirely on this device.",
    "ar" to "مرحبا، هذا الصوت يعمل بالكامل على هذا الجهاز.",
    "bg" to "Здравейте, този глас работи изцяло на това устройство.",
    "cs" to "Dobrý den, tento hlas běží celý na tomto zařízení.",
    "da" to "Hej, denne stemme kører helt på denne enhed.",
    "de" to "Hallo, diese Stimme läuft vollständig auf diesem Gerät.",
    "el" to "Γεια σας, αυτή η φωνή λειτουργεί εξ ολοκλήρου σε αυτή τη συσκευή.",
    "es" to "Hola, esta voz funciona por completo en este dispositivo.",
    "et" to "Tere, see hääl töötab täielikult selles seadmes.",
    "fi" to "Hei, tämä ääni toimii kokonaan tässä laitteessa.",
    "fr" to "Bonjour, cette voix fonctionne entièrement sur cet appareil.",
    "hi" to "नमस्ते, यह आवाज़ पूरी तरह इस उपकरण पर चलती है।",
    "hr" to "Zdravo, ovaj glas radi u cijelosti na ovom uređaju.",
    "hu" to "Üdvözlöm, ez a hang teljesen ezen a készüléken fut.",
    "id" to "Halo, suara ini berjalan sepenuhnya di perangkat ini.",
    "it" to "Ciao, questa voce funziona interamente su questo dispositivo.",
    "ja" to "こんにちは。この音声はすべてこの端末で動いています。",
    "ko" to "안녕하세요. 이 음성은 전부 이 기기에서 동작합니다.",
    "lt" to "Sveiki, šis balsas veikia visiškai šiame įrenginyje.",
    "lv" to "Sveiki, šī balss darbojas pilnībā šajā ierīcē.",
    "nl" to "Hallo, deze stem werkt volledig op dit apparaat.",
    "pl" to "Cześć, ten głos działa całkowicie na tym urządzeniu.",
    "pt" to "Olá, esta voz funciona inteiramente neste dispositivo.",
    "ro" to "Bună, această voce rulează complet pe acest dispozitiv.",
    "ru" to "Здравствуйте, этот голос работает полностью на этом устройстве.",
    "sk" to "Dobrý deň, tento hlas beží celý na tomto zariadení.",
    "sl" to "Zdravo, ta glas deluje popolnoma na tej napravi.",
    "sv" to "Hej, den här rösten körs helt på den här enheten.",
    "tr" to "Merhaba, bu ses tamamen bu cihazda çalışıyor.",
    "uk" to "Вітаю, цей голос працює повністю на цьому пристрої.",
    "vi" to "Xin chào, giọng nói này chạy hoàn toàn trên thiết bị này.",
)

private fun speakSample(
    context: android.content.Context,
    engine: androidx.compose.runtime.MutableState<TextToSpeech?>,
    languages: List<TtsVoiceUiState>,
    selectedCode: String,
    samples: Map<String, String>,
    onStatus: (String) -> Unit,
) {
    if (languages.isEmpty()) {
        onStatus("The bundled voices could not be read.")
        return
    }
    val chosen = languages.firstOrNull { it.code == selectedCode }
        ?: languages.first()
    val bcp47 = chosen.bcp47
    val sample = samples[chosen.code] ?: samples["en"]!!

    onStatus("Loading…")
    engine.value?.shutdown()
    var tts: TextToSpeech? = null
    tts = TextToSpeech(
        context,
        { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag(bcp47)
                tts?.speak(
                    sample,
                    TextToSpeech.QUEUE_FLUSH, null, "sample",
                )
                onStatus("")
            } else {
                onStatus("Engine failed to start.")
            }
        },
        context.packageName,
    )
    engine.value = tts
}

@Composable
private fun TtsTestCard(
    enabled: Boolean,
    languages: List<TtsVoiceUiState>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    status: String,
    onSpeak: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.try_the_voice), fontWeight = FontWeight.Bold)

            if (languages.isNotEmpty()) {
                // Language picker for the TTS test.
                VoiceLanguageDropdown(
                    languages = languages,
                    selectedCode = selectedCode,
                    onSelect = onSelect,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                enabled = enabled,
                onClick = onSpeak,
            ) { Text(stringResource(R.string.speak_sample)) }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The voice-language picker, built from [OutlinedButton] + [DropdownMenu] rather than the
 * library's `ExposedDropdownMenu` wrapper, which currently recurses into itself.
 */
@Composable
private fun VoiceLanguageDropdown(
    languages: List<TtsVoiceUiState>,
    selectedCode: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = languages.firstOrNull { it.code == selectedCode }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                selected?.let { "${it.nativeName} (${it.englishName})" } ?: selectedCode,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconArrowDropDown()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            languages.forEach { voice ->
                SelectableDropdownMenuItem(
                    selected = voice.code == selectedCode,
                    onClick = {
                        onSelect(voice.code)
                        expanded = false
                    },
                    text = { Text("${voice.nativeName} · ${voice.englishName} (${voice.code})") },
                    selectedLeadingIcon = { IconCheck() },
                )
            }
        }
    }
}
