package com.vayunmathur.games.solitaire.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.platform.SolitaireActions

/**
 * A card the player can pick up. [sourceId] identifies the pile position it comes from;
 * the cards the drag actually carries are derived from live game state by the view model,
 * so nothing here can go stale between recompositions.
 */
@Composable
fun DraggableCard(
    card: Card,
    sourceId: String,
    actions: SolitaireActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var startPos by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    // Hidden while a staged flight covers this card, so the flight — not a
    // duplicate underneath — is what the player sees travelling.
    val flight by actions.flightInfo.collectAsState()
    val isFlying = flight?.cards?.contains(card) == true
    // Feeds the animated auto-complete its launch points; unlaid cards (the stock)
    // stay absent and fly instantly instead.
    LaunchedEffect(card, startPos) {
        if (startPos != Offset.Zero) actions.registerCardPosition(card, startPos)
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                startPos = coords.positionInRoot()
                cardSize = coords.size
            }
            .pointerInput(card, sourceId) {
                detectTapGestures(
                    onTap = { actions.requestAutoMove(sourceId, startPos) },
                )
            }
            .pointerInput(card, sourceId) {
                detectDragGestures(
                    onDragStart = {
                        dragOffset = Offset.Zero
                        isDragging = actions.startDrag(sourceId, startPos, cardSize)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                        actions.updateDrag(startPos + dragOffset)
                    },
                    onDragEnd = {
                        val center = Offset(cardSize.width / 2f, cardSize.height / 2f)
                        actions.endDrag(startPos + dragOffset + center, cardSize)
                        isDragging = false
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = {
                        actions.cancelDrag()
                        isDragging = false
                        dragOffset = Offset.Zero
                    }
                )
            }
            .graphicsLayer {
                if (isDragging || isFlying) alpha = 0f
            }
    ) {
        content()
    }
}

@Composable
fun DragOverlay(
    actions: SolitaireActions,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    val dragInfo by actions.dragInfo.collectAsState()
    var overlayPos by remember { mutableStateOf(Offset.Zero) }

    val faceUpOverlap = 22.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .zIndex(Float.MAX_VALUE)
            .onGloballyPositioned { coords ->
                overlayPos = coords.positionInRoot()
            }
    ) {
        val info = dragInfo ?: return@Box
        val relX = (info.offset.x - overlayPos.x).toInt()
        val relY = (info.offset.y - overlayPos.y).toInt()

        Box(
            modifier = Modifier
                .offset { IntOffset(relX, relY) }
                .graphicsLayer { alpha = 0.9f }
        ) {
            info.cards.forEachIndexed { index, card ->
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
            actions.dropTargets[targetId] = androidx.compose.ui.geometry.Rect(
                pos.x, pos.y,
                pos.x + size.width, pos.y + size.height
            )
        }
    ) {
        content()
    }
}

