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
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.minesweeper.R
import com.vayunmathur.games.minesweeper.data.GameOutcome
import com.vayunmathur.games.minesweeper.data.MinesweeperGameState
import com.vayunmathur.library.ui.IconClear
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
                            cell = cell,
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
    cell: Dp,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
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
    // On a loss the field is opened up, so a flag on a safe cell is now known to be wrong: crossed
    // out, as in classic Minesweeper, so the player can see which deductions failed.
    val wrongFlag = showFlag && lost && !isMine

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
        wrongFlag -> stringResource(R.string.cd_wrong_flag)
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

            wrongFlag -> Box(Modifier.fillMaxSize(0.65f), contentAlignment = Alignment.Center) {
                IconFlag(Modifier.fillMaxSize(), tint = scheme.error.copy(alpha = WRONG_FLAG_ALPHA))
                IconClear(Modifier.fillMaxSize(), tint = scheme.onSurface, contentDescription = null)
            }

            showFlag -> IconFlag(Modifier.fillMaxSize(0.65f), tint = scheme.error)

            revealed && count > 0 -> Text(
                text = count.toString(),
                color = countColor(count, dark = scheme.surfaceContainerLowest.luminance() < HALF),
                fontWeight = FontWeight.Bold,
                // 15sp follows the font setting only until the digit fills the cell: a Large
                // field's ~27dp cells overflowed at 200% font, and the number spilled out.
                fontSize = density.fitSp(15.sp, (cell - CellGap * 2) * COUNT_CELL_FRACTION),
                lineHeight = COUNT_LINE_EM.em,
            )
        }
    }
}

/** Gap between cells, so the field reads as a grid without needing drawn lines. */
private val CellGap = 1.dp

/** A disproved flag is dimmed under its cross, so the cross reads first. */
private const val WRONG_FLAG_ALPHA = 0.45f

/** Luminance below which the revealed-cell background counts as dark. */
private const val HALF = 0.5f

/** A count never renders taller than this share of its cell. */
private const val COUNT_CELL_FRACTION = 0.7f

/** Line box of a count relative to its size, so the line cannot outgrow the cell. */
private const val COUNT_LINE_EM = 1.15f

/**
 * [preferred], unless at the current font scale it would render taller than [cap] — then [cap].
 * Compared in pixels, so Android 14's non-linear font scaling is handled.
 */
private fun Density.fitSp(preferred: TextUnit, cap: Dp): TextUnit =
    if (preferred.toPx() > cap.toPx()) cap.toSp() else preferred

/**
 * The traditional per-number colours, as a fixed palette with a light and a dark variant.
 *
 * They used to be theme roles (primary, tertiary, error, secondary, then onSurface for 5-8). In the
 * dark scheme several of those land on near-identical pastel tones, so 1, 2 and 4 were hard to tell
 * apart, and 5-8 were all one colour. A fixed palette keeps the classic hues - blue, green, red,
 * purple, amber, cyan, near-black, grey - eight distinct colours, each at least 4:1 against the
 * revealed-cell background of its variant. The variant follows the cell background rather than the
 * system setting, so an app-level theme override is respected.
 */
internal fun countColor(count: Int, dark: Boolean): Color =
    (if (dark) DarkCounts else LightCounts)[(count - 1).coerceIn(0, LightCounts.lastIndex)]

private val LightCounts = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFC62828), Color(0xFF6A1B9A),
    Color(0xFFA15C00), Color(0xFF00838F), Color(0xFF212121), Color(0xFF757575),
)

private val DarkCounts = listOf(
    Color(0xFF82B1FF), Color(0xFF81C784), Color(0xFFFF8A80), Color(0xFFCE93D8),
    Color(0xFFFFB74D), Color(0xFF4DD0E1), Color(0xFFF5F5F5), Color(0xFF9E9E9E),
)
