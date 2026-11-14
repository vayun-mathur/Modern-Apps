package com.vayunmathur.cast.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.cast.tv.R
import com.vayunmathur.library.tv.TvDimens

/**
 * The pairing code, very large and otherwise undecorated.
 *
 * The one thing on this screen the user reads from across a room and copies exactly, so it is
 * huge monospaced type with the same "123 456" grouping as ever, sitting in one card in the
 * phone app's card language: who is asking above it, tries left (or the replaced-code notice)
 * below it. No tiles, no borders, no glow.
 *
 * Nothing is focusable: there is no input on this screen (the typing happens on the phone), so
 * a focus target would be a thing the D-pad could reach that does nothing.
 *
 * The code is measured to fit, never assumed to (#919): most Android TVs, whatever their panel,
 * report a 960x540 dp screen, and the card is only ~470 dp wide there. The code takes whatever
 * height the other lines leave (so a large font scale shrinks the digits instead of pushing the
 * tries-left line off the card), and the sender line is capped at two lines so a long device
 * name cannot squeeze the code out.
 */
@Composable
fun PairingScreen(
    senderName: String,
    code: String,
    attemptsLeft: Int,
    codeChanged: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(
                start = TvDimens.SafeHorizontal,
                end = TvDimens.SafeHorizontal,
                top = TvDimens.SafeVertical,
                bottom = TvDimens.SafeVertical,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(fraction = CODE_CARD_WIDTH_FRACTION)
                .background(scheme.surface, MaterialTheme.shapes.medium)
                .padding(horizontal = 32.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.tv_pair_from, senderName),
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = SENDER_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.tv_pair_title),
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Code(code, Modifier.weight(1f, fill = false))
            if (codeChanged) {
                Text(
                    text = stringResource(R.string.tv_pair_new_code),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.error,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = pluralStringResource(R.plurals.tv_pair_attempts, attemptsLeft, attemptsLeft),
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The six digits, huge and monospaced, grouped the way they are read out.
 *
 * Auto-sized: as large as [CODE_MAX_SIZE_SP] allows, shrunk until the whole code fits the card's
 * width and the height left to it. Never wrapped: `softWrap = false` keeps "123  456" on one line,
 * where the old `maxLines = 1` with soft wrap on broke at the gap and silently dropped the second
 * group (#919). Line height and tracking are in `em` so they follow the chosen size, and the line
 * box is taller than the glyphs, so nothing is clipped at the baseline either (the old 64 sp
 * `displayLarge` line height under 96 sp digits cut their bottoms off once the text overflowed).
 */
@Composable
private fun Code(code: String, modifier: Modifier = Modifier) {
    BasicText(
        text = code.chunked(CODE_CHUNK).joinToString(separator = CODE_GAP),
        style = MaterialTheme.typography.displayLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = CODE_MAX_SIZE_SP.sp,
            lineHeight = CODE_LINE_HEIGHT_EM.em,
            letterSpacing = CODE_LETTER_SPACING_EM.em,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(
            minFontSize = CODE_MIN_SIZE_SP.sp,
            maxFontSize = CODE_MAX_SIZE_SP.sp,
            stepSize = CODE_SIZE_STEP_SP.sp,
        ),
        modifier = modifier,
    )
}

private const val CODE_CHUNK = 3
private const val CODE_GAP = "  "
private const val CODE_MAX_SIZE_SP = 96
private const val CODE_MIN_SIZE_SP = 24
private const val CODE_SIZE_STEP_SP = 2
private const val CODE_LINE_HEIGHT_EM = 1.2f
private const val CODE_LETTER_SPACING_EM = 0.04f
private const val CODE_CARD_WIDTH_FRACTION = 0.62f
private const val SENDER_MAX_LINES = 2
