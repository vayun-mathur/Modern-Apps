package com.vayunmathur.education.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.education.content.MatchingQuestion
import kotlin.math.roundToInt

/**
 * K-2 drag-and-drop matching: drag each right-hand chip onto its matching
 * left-hand target. Correct drops lock in; wrong drops gently snap back
 * (no penalty). [onSolved] fires once every target is correctly matched.
 */
@Composable
fun K2DragMatch(question: MatchingQuestion, enabled: Boolean, onSolved: () -> Unit) {
    val targetBounds = remember { mutableStateMapOf<Int, Rect>() }
    val chipBounds = remember { mutableStateMapOf<Int, Rect>() }
    val dragOffset = remember { mutableStateMapOf<Int, Offset>() }
    // left target index -> assigned right index (correct only)
    val assigned = remember { mutableStateMapOf<Int, Int>() }

    Row(
        Modifier
            .fillMaxWidth()
            .height(360.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Targets (left)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            question.left.forEachIndexed { li, text ->
                val matchedRight = assigned[li]
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { targetBounds[li] = it.boundsInRoot() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (matchedRight != null) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (matchedRight != null) "$text  ✓" else text,
                            fontSize = 28.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        // Draggable chips (right)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            question.right.forEachIndexed { ri, text ->
                if (assigned.containsValue(ri)) return@forEachIndexed
                val offset = dragOffset[ri] ?: Offset.Zero
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                        .onGloballyPositioned { chipBounds[ri] = it.boundsInRoot() }
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            detectDragGestures(
                                onDrag = { change, delta ->
                                    dragOffset[ri] = (dragOffset[ri] ?: Offset.Zero) + delta
                                    change.consume()
                                },
                                onDragEnd = {
                                    val center = chipBounds[ri]?.center
                                    val hit = center?.let { c ->
                                        targetBounds.entries.firstOrNull { it.value.contains(c) }?.key
                                    }
                                    if (hit != null &&
                                        !assigned.containsKey(hit) &&
                                        question.correctRightForLeft[hit] == ri
                                    ) {
                                        assigned[hit] = ri
                                        if (assigned.size == question.left.size) onSolved()
                                    } else {
                                        dragOffset[ri] = Offset.Zero
                                    }
                                },
                            )
                        },
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text, fontSize = 28.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
