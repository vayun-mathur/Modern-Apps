package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.data.sudokuSymbol
import com.vayunmathur.games.sudoku.platform.RejectedDigit
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.animatedColor
import kotlinx.coroutines.delay

/**
 * The digit keys, laid out as a grid in the board's own box shape.
 *
 * Reusing [SudokuGameState.size]'s box geometry means the pad is a miniature of a box — 2x3 for 6x6,
 * 3x3 for 9x9, 3x4 for 12x12 — so a digit sits in the same relative position on the pad as it does in
 * the pencil marks.
 *
 * Keys are fixed-size squares packed flush against each other rather than stretched across the width: a
 * full-width grid would give keys around 90dp across on a 12x12, which crowds out the board.
 *
 * They are drawn like the grid's own cells — background-coloured inside, outlined — rather than as
 * filled buttons, so a block of ten of them does not dominate the screen above the board it serves.
 *
 * In classic mode no key is ever disabled: a wrong digit is accepted silently, so there is nothing
 * to gate on, and a digit that already appears the maximum number of times may still be one the
 * player wants to move. In answer mode wrong digits can never land, so a digit with every copy on
 * the board is done — its key is greyed out — and a refused digit flashes red on its key.
 *
 * Every key carries a remaining-count badge: how many copies of the digit are still missing from
 * the grid. It counts what is on the board, not what is correct, so in classic mode it can sit at
 * zero while wrong entries hide elsewhere — the badge is a progress hint, not a verdict.
 * In notes mode the key stays a pure pencil-mark preview (see [DigitKey]) and shows no badge.
 *
 * @param rejected the last digit answer mode refused, with a sequence number telling repeats
 * apart. The pad flashes the matching key and settles back on its own; the state is never cleared,
 * so a correct entry landing mid-flash cannot cut it short.
 */
@Composable
fun NumberPad(
    game: SudokuGameState,
    onDigit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rejected: RejectedDigit? = null,
) {
    val size = game.size
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (row in 0 until size.boxRows) {
            Row {
                for (col in 0 until size.boxCols) {
                    val digit = row * size.boxCols + col + 1
                    DigitKey(
                        digit = digit,
                        size = size,
                        notesMode = game.notesMode,
                        // What is on the board, clamped: classic mode accepts duplicates, so the
                        // count can overshoot and the badge must never go negative.
                        remaining = (size.side - (0 until size.cellCount).count {
                            game.valueAt(it) == digit
                        }).coerceAtLeast(0),
                        // Only answer mode can complete a digit: classic entries may be wrong or
                        // moved, so the count proves nothing there.
                        enabled = !game.answerMode || !game.isDigitComplete(digit),
                        flashing = rejected?.digit == digit,
                        flashKey = rejected?.attempt,
                        onClick = { onDigit(digit) },
                    )
                }
            }
        }
    }
}

/**
 * One key.
 *
 * In notes mode the digit shrinks into the slot it would occupy as a pencil mark, so the key is a
 * preview of the mark it will place — which is also what tells the player the mode is on, without
 * needing a second colour for it. The preview fills the whole key, leaving no room for the
 * remaining-count badge, so notes-mode keys show none; the badge is back as soon as the key
 * writes digits again.
 *
 * A refused digit tints the key red and settles back on its own: [flashing] turns on when a
 * rejection names this key and [flashKey] tells repeats apart, so two wrong taps on one key flash
 * twice. The lit flag is local state rather than a round-trip through the ViewModel — a round-trip
 * would cut the flash short whenever a correct digit lands meanwhile.
 */
@Composable
private fun DigitKey(
    digit: Int,
    size: BoardSize,
    notesMode: Boolean,
    remaining: Int,
    enabled: Boolean,
    flashing: Boolean,
    flashKey: Long?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var lit by remember { mutableStateOf(false) }
    // Keyed on both: a second rejection names a new attempt even for the same digit, and a rejection
    // of another digit must switch this key's flash off — cancelling the delay would otherwise leave
    // it stuck red, since the cancelled coroutine never reaches the reset.
    LaunchedEffect(flashing, flashKey) {
        if (flashing && flashKey != null) {
            lit = true
            delay(FLASH_MILLIS)
            lit = false
        } else {
            lit = false
        }
    }
    // On the scheme's effects spec rather than a hand-rolled tween, so the fade matches the app.
    val background = animatedColor(
        when {
            lit -> scheme.errorContainer
            !enabled -> scheme.surfaceContainerHighest
            else -> scheme.background
        }
    )
    val contentColor = when {
        lit -> scheme.onErrorContainer
        // A finished digit reads as done, not broken: surface text rather than the error colour.
        !enabled -> scheme.onSurface.copy(alpha = DISABLED_ALPHA)
        else -> scheme.onSurface
    }
    // Pencil marks keep their own quieter colour, dimming only when the key itself is done or lit.
    val noteColor = when {
        lit -> scheme.onErrorContainer
        !enabled -> scheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        else -> scheme.onSurfaceVariant
    }
    val label = sudokuSymbol(digit)
    val density = LocalDensity.current
    // The key is a fixed 50dp, so the digit and the badge stacked under it are capped by the key:
    // at 200% font they used to total ~66sp of line boxes and both clipped.
    val digitSize = density.fitSp(FullFontSize, KeySize * KEY_DIGIT_FRACTION)
    val badgeSize = density.fitSp(BadgeFontSize, KeySize * KEY_BADGE_FRACTION)
    val description = stringResource(
        if (notesMode) R.string.cd_note_key else R.string.cd_digit_key,
        label,
    )
    Box(
        Modifier
            .size(KeySize)
            .background(background)
            .border(OutlineWidth, scheme.outline)
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (notesMode) {
            NoteSlot(digit = digit, label = label, size = size, contentColor = noteColor)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = label,
                    color = contentColor,
                    fontSize = digitSize,
                    // Tight line boxes: the badge below must fit the 50dp key with the digit.
                    lineHeight = TIGHT_LINE_EM.em,
                    textAlign = TextAlign.Center,
                )
                // Unspoken: the key's clearAndSetSemantics replaces the whole subtree, so the
                // count stays visual-only and the digit's description — and its tests — are
                // unchanged.
                Text(
                    text = remaining.toString(),
                    color = contentColor,
                    fontSize = badgeSize,
                    lineHeight = TIGHT_LINE_EM.em,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The digit drawn where it would sit among a cell's pencil marks.
 *
 * Lays out the full mini-grid and fills only this digit's slot, so every key positions its digit on the
 * same lattice — the pad reads as one cell's worth of marks spread across the keys.
 *
 * Sized well above a real pencil mark — see [NoteFontSize] — so the key stays readable, while its position
 * still tells the player exactly which mark the tap will place.
 */
@Composable
private fun NoteSlot(digit: Int, label: String, size: BoardSize, contentColor: Color) {
    val slotHeight = KeySize / size.boxRows
    val slotWidth = KeySize / size.boxCols
    val density = LocalDensity.current
    // Capped by the slot, and the line box in non-scaling sp: an sp line height grew with the
    // font setting and overflowed the slot, clipping the 3x4 pad's bottom row at 200%.
    val noteSize = density.fitSp(
        NoteFontSize,
        minOf(slotHeight * NOTE_SLOT_HEIGHT_FRACTION, slotWidth * NOTE_SLOT_WIDTH_FRACTION),
    )
    val noteLine = with(density) { slotHeight.toSp() }
    val target = digit - 1
    Column(Modifier.fillMaxSize()) {
        for (row in 0 until size.boxRows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                for (col in 0 until size.boxCols) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (row * size.boxCols + col == target) {
                            Text(
                                text = label,
                                color = contentColor,
                                fontSize = noteSize,
                                // Matched to the slot, or the glyph's own line box overflows it and the
                                // bottom row of a 3x4 pad is clipped.
                                lineHeight = noteLine,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Comfortably above the 48dp touch target once the outline is accounted for, and no larger. */
private val KeySize = 50.dp

private val OutlineWidth = 1.dp

/** Size of a digit that a tap will write into the grid. */
private val FullFontSize = 19.sp

/** Remaining-count badge under the digit: small enough to share the 50dp key with it. */
private val BadgeFontSize = 11.sp

/** Line box relative to font size for the digit and badge stacked in a key. */
private const val TIGHT_LINE_EM = 1.1f

/** Caps, as shares of the key, so digit + badge always fit: (0.42 + 0.22) * 1.1 < 1. */
private const val KEY_DIGIT_FRACTION = 0.42f
private const val KEY_BADGE_FRACTION = 0.22f

/** A notes-mode preview digit's cap, as shares of its slot's height and width. */
private const val NOTE_SLOT_HEIGHT_FRACTION = 0.8f
private const val NOTE_SLOT_WIDTH_FRACTION = 1.1f

/** How long a refused key stays red before settling back, in milliseconds. */
private const val FLASH_MILLIS = 450L

/** Alpha of a finished digit's key: reads as done rather than broken. */
private const val DISABLED_ALPHA = 0.38f

/**
 * Size of a digit a tap will pencil in.
 *
 * Deliberately much larger than a real pencil mark: the marks on the board are sized to fit twelve of
 * them in one cell, which is far smaller than a key needs, and a lone digit that small is hard to read.
 * Expressed as a fraction of [FullFontSize] so the two stay in step, and still fits the narrowest slot a
 * board asks for — a 12x12's 3x4 pad.
 */
private val NoteFontSize = FullFontSize * 0.7f
