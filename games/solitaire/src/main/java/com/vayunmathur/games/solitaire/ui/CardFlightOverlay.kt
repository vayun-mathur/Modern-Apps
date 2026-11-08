package com.vayunmathur.games.solitaire.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.solitaire.platform.SolitaireActions
import com.vayunmathur.library.ui.Motion
import kotlin.math.roundToInt

private val OffsetConverter = TwoWayConverter<Offset, AnimationVector2D>(
    convertToVector = { AnimationVector2D(it.x, it.y) },
    convertFromVector = { Offset(it.v1, it.v2) }
)

/** One auto-complete hop: snappy enough to stagger, slow enough to follow. */
private const val FastFlightMillis = 110

/**
 * Flies a staged tap-to-move from its tap point to its destination, then lands it.
 *
 * The flight carries root-frame coordinates; the overlay translates both ends into
 * its own frame, deriving the landing point from the target's registered drop
 * rectangle so the card arrives exactly on the pile. When the target is not laid
 * out (a stale flight after a mode switch), the move commits instantly instead of
 * stranding the cards mid-air. Stacking and translucency mirror [DragOverlay].
 */
@Composable
fun CardFlightOverlay(
    actions: SolitaireActions,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    val flight by actions.flightInfo.collectAsState()
    var overlayPos by remember { mutableStateOf(Offset.Zero) }
    val cardHeightPx = with(LocalDensity.current) { cardHeight.toPx() }

    val faceUpOverlap = 22.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE)
            .onGloballyPositioned { coords ->
                overlayPos = coords.positionInRoot()
            }
    ) {
        val current = flight ?: return@Box
        val target = actions.dropTargets[current.targetId]
        if (target == null) {
            LaunchedEffect(current) { actions.commitFlight() }
            return@Box
        }
        val from = Offset(current.from.x - overlayPos.x, current.from.y - overlayPos.y)
        val to = Offset(
            target.left - overlayPos.x,
            target.bottom - overlayPos.y - cardHeightPx
        )
        val position: Animatable<Offset, AnimationVector2D> =
            remember(current) { Animatable(from, OffsetConverter) }
        LaunchedEffect(current) {
            position.animateTo(
                to,
                if (current.fast) Motion.over<Offset>(FastFlightMillis) else Motion.drop<Offset>()
            )
            actions.commitFlight()
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(position.value.x.roundToInt(), position.value.y.roundToInt()) }
                .graphicsLayer { alpha = 0.9f }
        ) {
            current.cards.forEachIndexed { index, card ->
                CardFace(
                    card = card,
                    modifier = Modifier.offset(y = faceUpOverlap * index),
                    cardWidth = cardWidth,
                    cardHeight = cardHeight
                )
            }
        }
    }
}
