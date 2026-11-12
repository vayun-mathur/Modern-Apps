package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.vayunmathur.games.solitaire.platform.SolitaireActions

/**
 * A pile's drop zone. Its laid-out rectangle (root coordinates) is what drops resolve against,
 * and its top-left is the pile origin the motion layer lands flights relative to.
 */
@Composable
fun DropTarget(
    targetId: String,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    // The map outlives the board — it hangs off the view model, which is scoped to the
    // activity. A pile that leaves the composition has to take its rectangle with it, or a
    // mode with fewer piles (or piles somewhere else) keeps resolving drops against the
    // previous mode's geometry.
    DisposableEffect(targetId) {
        onDispose { actions.dropTargets.remove(targetId) }
    }
    Box(
        modifier = modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInRoot()
            val size = coords.size
            actions.dropTargets[targetId] = Rect(
                pos.x, pos.y,
                pos.x + size.width, pos.y + size.height
            )
        }
    ) {
        content()
    }
}

