package com.vayunmathur.library.ui.game

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton

/**
 * The card shown over a finished board.
 *
 * Drawn over a scrim so it reads as modal: without one, whatever the game puts under the board — a
 * number pad, an action row — shows around the card's edges and the board looks half-live.
 *
 * The scrim is also a touch barrier: it swallows every pointer event that lands outside the card, so a
 * tap meant to dismiss nothing cannot fall through to a cell or a number-pad key on the finished board.
 *
 * It is modal for assistive technology and keyboards too. The overlay is a pane titled [title]: it is
 * announced when it appears, and because a pane is important for accessibility and covers the board,
 * Compose leaves the covered board out of the tree TalkBack sees, so swiping cannot reach a cell whose
 * click action would bypass the touch barrier. Focus moves onto the overlay when it appears and cannot
 * leave it, so Tab and the D-pad stay on the card's buttons.
 *
 * [won] only picks the title colour; the caller supplies the words, because "Solved", "Field Cleared"
 * and "Congratulations" all mean the same thing in different games and none of them belongs here.
 * [stats] is a free slot for the lines a game wants to boast about, which is the one part that has
 * never been the same twice.
 *
 * "View board" is built in: it fades the card and scrim away and leaves a small bar at the bottom with
 * "Show result" and the primary action, so the player can look at the finished board — the solved grid,
 * where the mine was — before moving on. The board stays untouchable while viewed. Back returns to the
 * card.
 *
 * @param onPlayAgain primary action. Null hides it, for a daily board where there is nothing to replay;
 *   the board-view bar then offers [onBack] instead.
 * @param allowViewBoard false drops the "View board" action, for a game whose board says nothing more
 *   once it is over.
 */
@Composable
fun GameResultOverlay(
    title: String,
    won: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayAgain: (() -> Unit)? = null,
    playAgainLabel: String = stringResource(R.string.game_play_again),
    backLabel: String = stringResource(R.string.game_back),
    allowViewBoard: Boolean = true,
    stats: @Composable ColumnScope.() -> Unit = {},
) {
    var viewingBoard by rememberSaveable { mutableStateOf(false) }
    val showingBoard = allowViewBoard && viewingBoard
    // Back from the board view returns to the result rather than leaving the game behind it.
    if (showingBoard) BackHandler { viewingBoard = false }

    val focusRequester = remember { FocusRequester() }
    // Again on every switch: the focused button may just have faded out with the card or the bar.
    LaunchedEffect(showingBoard) { focusRequester.requestFocus() }
    Box(
        modifier
            .fillMaxSize()
            .modalPane(title),
    ) {
        // A sibling under the card rather than its parent: a parent that swallowed events would also
        // eat the moves of a press on the card's own buttons and cancel it. It stays in place while the
        // board is being viewed, which is for looking at: the game is over and must not take input.
        Box(
            Modifier
                .matchParentSize()
                .swallowPointerInput(),
        )
        // Where focus rests while the overlay shows: inside the pane, so the pane's exit guard holds it.
        // The guard only runs for a group whose descendant has focus, never for a focused node itself.
        Box(
            Modifier
                .matchParentSize()
                .focusRequester(focusRequester)
                .focusTarget(),
        )
        FadeVisibility(visible = !showingBoard, modifier = Modifier.matchParentSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .padding(Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        Modifier.padding(Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.headlineSmall,
                            color = if (won) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                        stats()
                        if (onPlayAgain != null) {
                            Button(onClick = onPlayAgain, Modifier.fillMaxWidth()) { Text(playAgainLabel) }
                        }
                        if (allowViewBoard) {
                            TextButton(onClick = { viewingBoard = true }, Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.game_view_board))
                            }
                        }
                        TextButton(onClick = onBack, Modifier.fillMaxWidth()) { Text(backLabel) }
                    }
                }
            }
        }
        FadeVisibility(
            visible = showingBoard,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(BarInsetSides))
                .padding(Spacing.lg),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = BAR_ELEVATION,
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { viewingBoard = false }) {
                        Text(stringResource(R.string.game_show_result))
                    }
                    // The card's primary action; a board with nothing to replay offers its way out instead.
                    if (onPlayAgain != null) {
                        Button(onClick = onPlayAgain) { Text(playAgainLabel) }
                    } else {
                        Button(onClick = onBack) { Text(backLabel) }
                    }
                }
            }
        }
    }
}

/**
 * The accessibility and focus half of being modal.
 *
 * A pane title makes the node important for accessibility, and Compose leaves out of the accessibility
 * tree any node wholly covered by an important node drawn above it — that is what hides the board from
 * TalkBack. The node is also a focus group that refuses to let focus leave it.
 */
private fun Modifier.modalPane(title: String): Modifier = this
    .semantics { paneTitle = title }
    .focusProperties { onExit = { cancelFocusChange() } }
    .focusGroup()

/**
 * Makes this node a hit target that consumes everything it receives, so nothing drawn beneath it — and
 * no ancestor gesture detector — sees the event.
 */
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}

/** Dark enough to mute the board behind, light enough to keep it recognisable. */
private const val SCRIM_ALPHA = 0.7f

/** Lifts the board-view bar off a board that may be any colour under it. */
private val BAR_ELEVATION = 6.dp

/** The bar sits on the bottom edge, so it keeps clear of the navigation bar and any side cutout. */
private val BarInsetSides = WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
