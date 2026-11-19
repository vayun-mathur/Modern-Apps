package com.vayunmathur.tuner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.tuner.R
import com.vayunmathur.tuner.data.InstrumentKind
import com.vayunmathur.tuner.domain.ChordLabel
import com.vayunmathur.tuner.domain.ChordName
import com.vayunmathur.tuner.domain.ChordPresentation
import com.vayunmathur.tuner.domain.ChordTier
import com.vayunmathur.tuner.domain.Voicing
import com.vayunmathur.tuner.domain.spell
import com.vayunmathur.tuner.domain.spellPitchClass
import com.vayunmathur.tuner.platform.TunerUiState

/**
 * The polyphonic tab.
 *
 * The layout follows the honest ordering of what the app knows: the **notes** are a measurement
 * and come with the keyboard; the **chord name** is an interpretation layered on top and is
 * withheld, hedged or shown twice according to [ChordPresentation]; the **fret diagram** is only
 * a suggestion, because which position was fingered is not recoverable from audio at all.
 */
@Composable
fun ChordScreen(state: TunerUiState, onSelectInstrument: (String) -> Unit) {
    AppScaffold(
        title = stringResource(R.string.tab_chord),
        actions = { InstrumentMenu(state, onSelectInstrument) },
    ) { padding ->
        // Single-pane instrument readout; letterboxed on expanded windows.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val reading = state.chord.reading
            val label = reading?.label ?: ChordLabel.Silent
            val presentation = reading?.presentation ?: ChordPresentation.LISTENING

            // Which reading of an ambiguous chord the diagrams below belong to. Local, because
            // it is a way of looking at one frame, not a change to what was detected.
            var showSecondary by remember(label) { mutableStateOf(false) }
            val ambiguous = label as? ChordLabel.Ambiguous
            val chosen: ChordName? = when {
                ambiguous != null -> if (showSecondary) ambiguous.secondary else ambiguous.primary
                label is ChordLabel.Chord -> label.name
                else -> null
            }

            Text(
                text = headlineText(label, presentation),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
                color = if (presentation == ChordPresentation.CONFIDENT) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )

            if (ambiguous != null) {
                Text(
                    text = stringResource(
                        R.string.chord_alternate,
                        chordText(if (showSecondary) ambiguous.primary else ambiguous.secondary),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.chord_ambiguous_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = { showSecondary = !showSecondary }) {
                    Text(
                        stringResource(
                            R.string.chord_show_other,
                            chordText(if (showSecondary) ambiguous.primary else ambiguous.secondary),
                        ),
                    )
                }
            }

            if (presentation == ChordPresentation.UNCERTAIN) {
                Text(
                    text = stringResource(R.string.confidence_low),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = stringResource(R.string.confidence_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (reading != null && reading.tier == ChordTier.PROVISIONAL &&
                presentation != ChordPresentation.LISTENING
            ) {
                Text(
                    text = stringResource(R.string.confidence_settling),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            // The note list and the keyboard are the measurement, so they survive every state
            // above "listening" - including the ones where the name is withheld.
            if (reading != null && reading.notes.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = stringResource(R.string.notes_detected),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = reading.notes.joinToString(SEPARATOR) { spell(it.midi).toString() },
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                        )
                        // The keyboard belongs to keyboard instruments only. A fretted
                        // instrument's diagram is the chord box below, which needs a name.
                        if (state.instrument.kind == InstrumentKind.KEYBOARD) {
                            PianoKeyboard(reading.notes.map { it.midi })
                        }
                    }
                }
            }

            // A diagram is a claim about a *named* chord, so it never appears without one.
            if (state.instrument.kind == InstrumentKind.FRETTED && chosen != null) {
                val voicings = if (showSecondary) {
                    state.chord.alternateVoicings
                } else {
                    state.chord.voicings
                }
                Shapes(state, voicings, chosen)
            }
        }
        }
    }
}

@Composable
private fun Shapes(state: TunerUiState, voicings: List<Voicing>, chord: ChordName) {
    Text(
        text = stringResource(R.string.shapes_heading),
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text = stringResource(R.string.shapes_caveat),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    if (voicings.isEmpty()) {
        Text(
            text = stringResource(R.string.shapes_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Rebuilt when the chord changes so the pager never opens on a stale page, and opened at
    // the recommended shape rather than at the lowest one.
    key(chord) {
        val primary = voicings.indexOfFirst { it.isPrimary }.coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = primary, pageCount = { voicings.size })
        HorizontalPager(state = pagerState) { page ->
            val voicing = voicings[page]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FretDiagram(state.instrument, voicing)
                Text(
                    text = if (voicing.position == 0) {
                        stringResource(R.string.position_open)
                    } else {
                        stringResource(R.string.position_fret, voicing.position)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (voicing.isPrimary) {
                    Text(
                        text = stringResource(R.string.shape_recommended),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun headlineText(label: ChordLabel, presentation: ChordPresentation): String = when {
    presentation == ChordPresentation.LISTENING -> stringResource(R.string.chord_prompt)
    presentation == ChordPresentation.NOTES_ONLY -> stringResource(R.string.chord_unsure)
    label is ChordLabel.SingleNote -> spell(label.midi).toString()
    label is ChordLabel.Interval -> stringResource(intervalLabel(label.semitones))
    label is ChordLabel.Ambiguous -> chordText(label.primary)
    label is ChordLabel.Chord -> chordText(label.name)
    else -> stringResource(R.string.chord_unsure)
}

/** Chord symbols are notation rather than prose, so they are built rather than translated. */
internal fun chordText(name: ChordName): String {
    val head = spellPitchClass(name.root) + name.quality.symbol
    return name.bass?.let { "$head/${spellPitchClass(it)}" } ?: head
}

private fun intervalLabel(semitones: Int): Int = when (semitones) {
    1 -> R.string.interval_minor_second
    2 -> R.string.interval_major_second
    3 -> R.string.interval_minor_third
    4 -> R.string.interval_major_third
    5 -> R.string.interval_perfect_fourth
    6 -> R.string.interval_tritone
    7 -> R.string.interval_perfect_fifth
    8 -> R.string.interval_minor_sixth
    9 -> R.string.interval_major_sixth
    10 -> R.string.interval_minor_seventh
    11 -> R.string.interval_major_seventh
    else -> R.string.interval_unison
}

private const val SEPARATOR = "  "
