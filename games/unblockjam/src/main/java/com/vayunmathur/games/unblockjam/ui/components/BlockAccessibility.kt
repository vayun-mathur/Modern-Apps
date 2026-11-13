package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics

/** The spoken name of a block and of the moves it can make, resolved by the caller. */
internal class BlockLabels(
    val description: String,
    val left: String,
    val right: String,
    val up: String,
    val down: String,
)

/**
 * Makes a block playable without dragging: TalkBack announces it and offers "Move left/right"
 * (or "up/down") actions, and with a keyboard a focused block steps with the arrow keys.
 *
 * [onStep] makes one one-cell step and says whether it happened; a refused step reports failure
 * so TalkBack can say so. With [movable] false (a wall, or a won board) the block is described
 * but offers no actions and takes no focus.
 */
internal fun Modifier.blockAccessibility(
    labels: BlockLabels,
    horizontal: Boolean,
    movable: Boolean,
    interactionSource: MutableInteractionSource,
    onStep: (dx: Int, dy: Int) -> Boolean,
): Modifier = this
    .semantics {
        contentDescription = labels.description
        if (movable) {
            customActions = if (horizontal) {
                listOf(
                    CustomAccessibilityAction(labels.left) { onStep(-1, 0) },
                    CustomAccessibilityAction(labels.right) { onStep(1, 0) },
                )
            } else {
                listOf(
                    CustomAccessibilityAction(labels.up) { onStep(0, -1) },
                    CustomAccessibilityAction(labels.down) { onStep(0, 1) },
                )
            }
        }
    }
    .onKeyEvent { event ->
        if (!movable || event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
            Key.DirectionLeft -> onStep(-1, 0)
            Key.DirectionRight -> onStep(1, 0)
            Key.DirectionUp -> onStep(0, -1)
            Key.DirectionDown -> onStep(0, 1)
            else -> false
        }
    }
    .focusable(enabled = movable, interactionSource = interactionSource)
