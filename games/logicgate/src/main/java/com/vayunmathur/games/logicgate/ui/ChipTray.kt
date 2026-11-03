package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.model.ChipSpec
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlin.math.abs

/** A chip being dragged out of the tray, at [root] in root coordinates. */
data class TrayDrag(val chipId: String, val root: Offset)

/**
 * The chip palette. Pull a card up onto the board to place it where you let go, or tap it to open
 * its info sheet (which can also place it). Sideways swipes scroll the row.
 */
@Composable
fun ChipTray(
    chips: List<ChipSpec>,
    onTap: (String) -> Unit,
    onDragUpdate: (TrayDrag?) -> Unit,
    onDrop: (TrayDrag) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(chips, key = { it.id }) { chip ->
                ChipCard(chip, onTap, onDragUpdate, onDrop)
            }
        }
    }
}

@Composable
private fun ChipCard(
    chip: ChipSpec,
    onTap: (String) -> Unit,
    onDragUpdate: (TrayDrag?) -> Unit,
    onDrop: (TrayDrag) -> Unit,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val description = stringResource(R.string.lg_cd_chip, chip.label, chip.nandCost)
    val widest = (chip.inputs + chip.outputs).maxOfOrNull { it.width } ?: 1
    Surface(
        onClick = { onTap(chip.id) },
        modifier = Modifier
            .height(CARD_HEIGHT)
            .widthIn(min = CARD_MIN_WIDTH)
            .onGloballyPositioned { coords = it }
            .semantics { contentDescription = description }
            .pointerInput(chip.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Only an upward pull claims the gesture; anything sideways is left
                    // unconsumed so the row scrolls instead.
                    val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
                        if (over.y < 0 && abs(over.y) > abs(over.x)) change.consume()
                    } ?: return@awaitEachGesture
                    var current = TrayDrag(chip.id, coords?.localToRoot(start.position) ?: return@awaitEachGesture)
                    onDragUpdate(current)
                    val finished = drag(start.id) { change ->
                        current = current.copy(root = current.root + change.positionChange())
                        change.consume()
                        onDragUpdate(current)
                    }
                    if (finished) onDrop(current)
                    onDragUpdate(null)
                }
            },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(chip.label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(chip.nandCost.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (widest > 1) {
                    Text(
                        stringResource(R.string.lg_bus_badge, widest),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (widest == 4) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private val CARD_HEIGHT = 52.dp
private val CARD_MIN_WIDTH = 64.dp
