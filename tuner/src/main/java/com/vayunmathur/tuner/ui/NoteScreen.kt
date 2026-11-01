package com.vayunmathur.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.domain.TuningBand
import com.vayunmathur.tuner.platform.TunerUiState

/**
 * The monophonic tab: one note, its frequency, and how far it is from the target.
 *
 * Nothing here relates to an instrument. Pitch detection is instrument-agnostic - it reports the
 * nearest note to whatever it hears - so a string number, a tuning target or an instrument
 * chooser would all be describing a selection the reading does not depend on. The reference is
 * likewise fixed at concert pitch.
 *
 * Stateless so the store-listing previews can render it from literals.
 */
@Composable
fun NoteScreen(state: TunerUiState) {
    AppScaffold(
        title = stringResource(R.string.tab_note),
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Single-pane instrument readout; letterboxed on expanded windows so the
        // meter and status keep a readable measure on desktop.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val readout = state.note.readout
            Text(
                text = readout?.let { "${it.letter}${it.octave}" } ?: EMPTY_NOTE,
                style = MaterialTheme.typography.displayLarge,
                color = if (readout == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            TuningMeter(cents = readout?.cents, band = readout?.band ?: TuningBand.OUT)

            if (readout == null) {
                Text(
                    text = stringResource(
                        when {
                            state.failure != null -> R.string.capture_unavailable
                            state.listening -> R.string.listening
                            else -> R.string.not_listening
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.failure != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = if (readout.band == TuningBand.IN_TUNE) {
                        stringResource(R.string.in_tune)
                    } else {
                        stringResource(R.string.cents_value, formatSigned(readout.cents))
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    color = when (readout.band) {
                        TuningBand.IN_TUNE -> MaterialTheme.colorScheme.primary
                        TuningBand.CLOSE -> MaterialTheme.colorScheme.tertiary
                        TuningBand.OUT -> MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    text = stringResource(R.string.frequency_value, formatHz(readout.frequencyHz)),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        }
    }
}

private const val EMPTY_NOTE = "\u2013"

internal fun formatHz(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

internal fun formatSigned(value: Double): String =
    String.format(java.util.Locale.US, "%+.1f", value)
