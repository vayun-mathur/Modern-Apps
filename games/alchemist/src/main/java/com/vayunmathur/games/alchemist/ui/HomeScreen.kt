package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.alchemist.Alchemist
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

data class PlacedItem(
    val id: Long,
    val offset: Offset,
    val key: Long = System.nanoTime()
)

@Composable
fun HomeScreen(backStack: NavBackStack<Route>, ds: DataStoreUtils) {
    val availableItems by remember {
        ds.stringSetFlow("available_items").map { set -> set.map { it.toLong() }.toSet() }
    }.collectAsState(initial = emptySet())

    LaunchedEffect(availableItems) {
        if (availableItems.isEmpty()) {
            (1L..4L).forEach { ds.addStringToSet("available_items", it.toString()) }
        }
    }

    val activeItems = remember { mutableStateListOf<PlacedItem>() }
    var screenWidth by remember { mutableFloatStateOf(0f) }
    var panelWidth by remember { mutableFloatStateOf(0f) }
    var playAreaOffsetInWindow by remember { mutableStateOf(Offset.Zero) }

    // Tracking for the current item being "pulled out" of the sidebar
    var draggingInventoryId by remember { mutableStateOf<Long?>(null) }
    var draggingInventoryOffset by remember { mutableStateOf(Offset.Zero) }

    var contextMenuElementId by remember { mutableStateOf<Long?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.onGloballyPositioned { screenWidth = it.size.width.toFloat() }
    ) { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. PLAY AREA (Full Screen)
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { playAreaOffsetInWindow = it.positionInWindow() }
            ) {
                activeItems.forEach { item ->
                    key(item.key) {
                        DraggableElement(
                            item = item,
                            onOffsetChanged = { newOffset ->
                                val index = activeItems.indexOfFirst { it.key == item.key }
                                if (index != -1) {
                                    activeItems[index] = activeItems[index].copy(offset = newOffset)
                                }
                            },
                            onDragEnd = { finalOffset ->
                                // DELETION: Triggered if any part of the item touches the sidebar
                                // screenWidth - panelWidth is the exact left edge of the sidebar.
                                if (finalOffset.x > (screenWidth - panelWidth - 72f)) {
                                    activeItems.removeAll { it.key == item.key }
                                } else {
                                    checkCombinations(item.key, finalOffset, activeItems) { toRemove, toAdd ->
                                        activeItems.removeAll { it.key in toRemove.map { r -> r.key } }
                                        activeItems.addAll(toAdd)
                                        toAdd.forEach { newItem ->
                                            if (newItem.id !in availableItems) {
                                                ds.addStringToSet("available_items", newItem.id.toString())
                                            }
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                contextMenuElementId = item.id
                                contextMenuExpanded = true
                            }
                        )
                    }
                }
            }

            // 2. SIDE PANEL (Overlay)
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(100.dp)
                    .align(Alignment.CenterEnd)
                    .onGloballyPositioned { panelWidth = it.size.width.toFloat() },
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(availableItems.toList(), key = { it }) { id ->
                        val itemData = Alchemist.items.find { it.id == id }
                        var itemPosInWindow by remember { mutableStateOf(Offset.Zero) }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .onGloballyPositioned { itemPosInWindow = it.positionInWindow() }
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            draggingInventoryId = id
                                            val fingerInWindow = itemPosInWindow + startOffset
                                            draggingInventoryOffset = Offset(
                                                x = fingerInWindow.x - playAreaOffsetInWindow.x - 100f,
                                                y = fingerInWindow.y - playAreaOffsetInWindow.y - 100f
                                            )
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            draggingInventoryOffset += dragAmount
                                        },
                                        onDragEnd = {
                                            // Final Check: Drop it if it's clear of the sidebar
                                            // Using -72f to ensure the icon is fully out before adding to board
                                            if (draggingInventoryOffset.x < (screenWidth - panelWidth - 72f)) {
                                                val newItem = PlacedItem(id, draggingInventoryOffset)
                                                activeItems.add(newItem)

                                                checkCombinations(newItem.key, newItem.offset, activeItems) { toRemove, toAdd ->
                                                    activeItems.removeAll { it.key in toRemove.map { r -> r.key } }
                                                    activeItems.addAll(toAdd)
                                                    toAdd.forEach { res ->
                                                        if (res.id !in availableItems) {
                                                            ds.addStringToSet("available_items", res.id.toString())
                                                        }
                                                    }
                                                }
                                            }
                                            draggingInventoryId = null
                                        },
                                        onDragCancel = {
                                            draggingInventoryId = null
                                        }
                                    )
                                }
                        ) {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .combinedClickable(
                                        onLongClick = {
                                            contextMenuElementId = id
                                            contextMenuExpanded = true
                                        },
                                        onClick = {}
                                    )
                            ) {
                                DynamicAlchemyIcon(id)
                            }
                            Text(itemData?.name ?: "", fontSize = 10.sp)
                        }
                    }
                }
            }

            // 3. GLOBAL DRAG OVERLAY
            draggingInventoryId?.let { id ->
                Box(
                    Modifier
                        .offset {
                            IntOffset(
                                draggingInventoryOffset.x.roundToInt(),
                                draggingInventoryOffset.y.roundToInt()
                            )
                        }
                        .size(72.dp)
                ) {
                    DynamicAlchemyIcon(id)
                }
            }
        }

        if (contextMenuExpanded) {
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("See details") },
                    onClick = {
                        contextMenuExpanded = false
                        contextMenuElementId?.let { backStack.add(Route.ItemDetails(it.toInt())) }
                    }
                )
            }
        }
    }
}

@Composable
fun DraggableElement(
    item: PlacedItem,
    onOffsetChanged: (Offset) -> Unit,
    onDragEnd: (Offset) -> Unit,
    onLongClick: () -> Unit
) {
    var currentOffset by remember(item.key) { mutableStateOf(item.offset) }

    Box(
        Modifier
            .offset { IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt()) }
            .size(72.dp)
            .combinedClickable(onLongClick = onLongClick, onClick = {})
            .pointerInput(item.key) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOffset += dragAmount
                        onOffsetChanged(currentOffset)
                    },
                    onDragEnd = { onDragEnd(currentOffset) }
                )
            }
    ) {
        DynamicAlchemyIcon(item.id)
    }
}

fun checkCombinations(
    movedKey: Long,
    movedOffset: Offset,
    currentItems: List<PlacedItem>,
    onCombined: (toRemove: List<PlacedItem>, toAdd: List<PlacedItem>) -> Unit
) {
    val movedItem = currentItems.find { it.key == movedKey } ?: return
    val others = currentItems.filter { it.key != movedKey }

    val target = others.find { other ->
        (other.offset - movedOffset).getDistance() < 100f
    }

    if (target != null) {
        val recipe = Alchemist.recipes.find {
            it.inputs.size == 2 && it.inputs.contains(movedItem.id) && it.inputs.contains(target.id)
        }
        if (recipe != null) {
            onCombined(listOf(movedItem, target), recipe.outputs.map { PlacedItem(it, target.offset) })
        }
    }
}