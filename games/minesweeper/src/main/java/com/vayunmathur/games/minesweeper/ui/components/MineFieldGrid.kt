package com.vayunmathur.games.minesweeper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.library.ui.ColorScheme
import com.vayunmathur.library.ui.IconFlag
import com.vayunmathur.library.ui.IconMine
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text

/**
 * The mine field.
 *
 * Cells are square and sized to the smaller of `width / cols` and `height / rows`, so the whole
 * field always fits the space it is given — never wider or taller, never scrolled. Width-only sizing
 * made a landscape field far taller than the screen. With unbounded height (a scrolling parent) this
 * falls back to width sizing. The grid is centred in any space it does not fill.
 *
 * Tap and long press are handled per cell by [detectTapGestures] rather than by `clickable` +
 * `combinedClickable`, because the flag gesture needs to fire on press-and-hold without also
 * delivering the tap that would open the cell.
 *
 * @param onLongPress returns whether a flag actually changed; the press buzzes only then.
 */
@Composable
fun MineFieldGrid(
    game: MinesweeperGameState,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Whole pixels: a fractional dp cell rounds up per cell, and 18 rounded-up rows overflow
        // the height they were fitted to, squashing the last rows.
        val heightPx = if (constraints.hasBoundedHeight) constraints.maxHeight / game.rows else Int.MAX_VALUE
        val cellPx = minOf(constraints.maxWidth / game.cols, heightPx)
        val cell = with(LocalDensity.current) { cellPx.toDp() }
        Column(Modifier.size(cell * game.cols, cell * game.rows)) {
            for (row in 0 until game.rows) {
                Row {
                    for (col in 0 until game.cols) {
                        val index = row * game.cols + col
                        MineCell(
                            game = game,
                            index = index,
                            onTap = { onTap(index) },
                            onLongPress = { onLongPress(index) },
                            modifier = Modifier.size(cell),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MineCell(
    game: MinesweeperGameState,
    index: Int,
    onTap: () -> Unit,
    onLongPress: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    val revealed = game.revealed[index]
    val flagged = game.flagged[index]
    val isMine = game.mines[index]
    val count = game.neighbourCounts[index]
    val lost = game.outcome == GameOutcome.LOST

    // Mines are hidden during play - they are the thing being deduced. On a loss the field is opened
    // up; on a win the mines are shown as the flags the player earned rather than as bare mines.
    val showMine = isMine && (revealed || lost)
    val showFlag = flagged && !showMine

    val background = when {
        index == game.explodedAt -> scheme.error
        revealed || showMine -> scheme.surfaceContainerLowest
        else -> scheme.surfaceContainerHighest
    }

    val position = stringResource(
        R.string.cd_position,
        index / game.cols + 1,
        index % game.cols + 1,
    )
    val stateWord = when {
        showMine -> stringResource(R.string.cd_mine)
        showFlag -> stringResource(R.string.cd_flagged)
        !revealed -> stringResource(R.string.cd_covered)
        count == 0 -> stringResource(R.string.cd_empty)
        else -> pluralStringResource(R.plurals.cd_count, count, count)
    }

    Box(
        modifier
            .padding(CellGap)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(background)
            .pointerInput(index, game.isOver, game.started) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        // Confirms the flag without the player having to look away from the field.
                        // Only for a real change: buzzing for a press that did nothing (an open
                        // cell, an unstarted field) told the player a flag went in when none did.
                        if (onLongPress()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    },
                )
            }
            .clearAndSetSemantics {
                contentDescription = "$position, $stateWord"
                // Screen-reader activation. Semantics-only on purpose: a clickable
                // modifier would also deliver a tap on press-and-hold and break the
                // tap/long-press split that detectTapGestures above preserves.
                onClick { onTap(); true }
                onLongClick {
                    if (onLongPress()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            showMine -> IconMine(
                Modifier.fillMaxSize(0.7f),
                tint = if (index == game.explodedAt) scheme.onError else scheme.onSurfaceVariant,
            )

            showFlag -> IconFlag(Modifier.fillMaxSize(0.65f), tint = scheme.error)

            revealed && count > 0 -> Text(
                text = count.toString(),
                color = countColor(count, scheme),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

/** Gap between cells, so the field reads as a grid without needing drawn lines. */
private val CellGap = 1.dp

/**
 * The traditional per-number colours, mapped onto theme roles so they still work in both light and
 * dark and against a dynamic palette. Beyond four the numbers are rare enough that one shared colour
 * is fine, and the count is bold enough to read regardless.
 */
private fun countColor(count: Int, scheme: ColorScheme): Color = when (count) {
    1 -> scheme.primary
    2 -> scheme.tertiary
    3 -> scheme.error
    4 -> scheme.secondary
    else -> scheme.onSurface
}
