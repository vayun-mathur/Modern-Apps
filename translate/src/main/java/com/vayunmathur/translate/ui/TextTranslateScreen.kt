package com.vayunmathur.translate.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.translate.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconPhotoLibrary
import com.vayunmathur.library.ui.IconSpeak
import com.vayunmathur.library.ui.IconStop
import com.vayunmathur.library.ui.IconSwapLanguages
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.MicState
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState

/**
 * The text translation screen, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@Composable
fun TextTranslateScreen(state: TextTranslateUiState, actions: TextTranslateActions) {
    AppScaffold(
        title = stringResource(R.string.app_name),
        actions = {
            IconButton(onClick = actions::openHistory) {
                IconHistory()
            }
            IconButton(onClick = actions::openImage) {
                IconPhotoLibrary()
            }
            IconButton(onClick = actions::openCamera) {
                IconCamera()
            }
        },
        floatingActionButton = {
            // Idle → Mic (starts). Listening → Stop (ends capture). Transcribing → a spinner
            // that ignores taps, so the user can't double-start or hit "busy".
            FloatingActionButton(
                onClick = actions::toggleMic,
                containerColor = when (state.micState) {
                    MicState.LISTENING -> MaterialTheme.colorScheme.errorContainer
                    MicState.TRANSCRIBING -> MaterialTheme.colorScheme.surfaceVariant
                    MicState.IDLE -> MaterialTheme.colorScheme.primaryContainer
                },
            ) {
                when (state.micState) {
                    MicState.LISTENING -> IconStop()
                    MicState.TRANSCRIBING -> CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                    MicState.IDLE -> IconMic()
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LanguageBar(
                sourceCode = state.sourceLang,
                targetCode = state.targetLang,
                onSource = { actions.openLanguagePicker(true) },
                onTarget = { actions.openLanguagePicker(false) },
                onSwap = actions::swap,
            )

            // On expanded widths the source and target panels sit side by side in
            // fractional columns; compact stays stacked. The camera overlay is
            // untouched — it stays full-bleed/fullscreen via FullscreenPage.
            if (isExpandedWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SourcePanel(
                            inputText = state.inputText,
                            micState = state.micState,
                            speechError = state.speechError,
                            onInput = actions::setInput,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutputCard(
                            translationAvailable = state.translationAvailable,
                            isTranslating = state.isTranslating,
                            outputText = state.outputText,
                            isFavorite = state.isFavorite,
                            canSave = state.canSave,
                            onCopy = actions::copyOutput,
                            onSpeak = actions::speakOutput,
                            onToggleFavorite = actions::toggleFavorite,
                        )
                    }
                }
            } else {
                SourcePanel(
                    inputText = state.inputText,
                    micState = state.micState,
                    speechError = state.speechError,
                    onInput = actions::setInput,
                )

                OutputCard(
                    translationAvailable = state.translationAvailable,
                    isTranslating = state.isTranslating,
                    outputText = state.outputText,
                    isFavorite = state.isFavorite,
                    canSave = state.canSave,
                    onCopy = actions::copyOutput,
                    onSpeak = actions::speakOutput,
                    onToggleFavorite = actions::toggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun SourcePanel(
    inputText: String,
    micState: MicState,
    speechError: String?,
    onInput: (String) -> Unit,
) {
    OutlinedTextField(
        value = inputText,
        onValueChange = onInput,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        label = { Text(stringResource(R.string.enter_text)) },
        minLines = 4,
    )

    val micStatus = when (micState) {
        MicState.LISTENING -> "Listening…"
        MicState.TRANSCRIBING -> "Transcribing…"
        MicState.IDLE -> null
    }
    micStatus?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )
    }
    speechError?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LanguageBar(
    sourceCode: String,
    targetCode: String,
    onSource: () -> Unit,
    onTarget: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        TextButton(onClick = onSource, modifier = Modifier.width(140.dp)) {
            Text(
                text = Languages.displayName(sourceCode),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onSwap,
            // Nothing to swap into while the source is auto-detect.
            enabled = sourceCode != Languages.AUTO.code,
        ) {
            IconSwapLanguages()
        }
        TextButton(onClick = onTarget, modifier = Modifier.width(140.dp)) {
            Text(
                text = Languages.displayName(targetCode),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OutputCard(
    translationAvailable: Boolean,
    isTranslating: Boolean,
    outputText: String,
    isFavorite: Boolean,
    canSave: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!translationAvailable) {
                // Should never happen — the outer InitialModelDownloadChecker
                // downloads the model before any Navigation is composed, and
                // TranslateViewModel loads the engine on init. Show a loading
                // spinner if the engine is still initializing.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.loading_translator), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.translation),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (isTranslating) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            Text(
                text = outputText.ifBlank { "Translation will appear here" },
                color = if (outputText.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCopy, enabled = outputText.isNotBlank()) {
                    IconCopy(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.copy))
                }
                TextButton(onClick = onSpeak, enabled = outputText.isNotBlank()) {
                    IconSpeak(Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.speak))
                }
                TextButton(onClick = onToggleFavorite, enabled = canSave) {
                    IconFavorite(
                        Modifier.size(18.dp),
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(
                            if (isFavorite) R.string.saved else R.string.save,
                        ),
                    )
                }
            }
        }
    }
}
