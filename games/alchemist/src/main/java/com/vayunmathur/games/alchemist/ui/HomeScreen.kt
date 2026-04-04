package com.vayunmathur.games.alchemist.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
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
    val availableItems by remember { ds.stringSetFlow("available_items").map { set -> set.map { it.toLong() } }}.collectAsState(initial = emptySet())

    // Initialize available items to 0-3 if empty
    LaunchedEffect(availableItems) {
        if (availableItems.isEmpty()) {
            (0L..3L).forEach { ds.addStringToSet("available_items", it.toString()) }
        }
    }

    var activeItems by remember { mutableStateOf(listOf<PlacedItem>()) }
    var panelWidth by remember { mutableFloatStateOf(0f) }
    var screenWidth by remember { mutableFloatStateOf(0f) }
    var currentDraggingKey by remember { mutableStateOf<Long?>(null) }
    
    var contextMenuElementId by remember { mutableStateOf<Long?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.onGloballyPositioned { screenWidth = it.size.width.toFloat() }
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            // Main play area
            Box(Modifier.fillMaxSize()) {
                activeItems.forEach { item ->
                    DraggableElement(
                        id = item.id,
                        initialOffset = item.offset,
                        onDragEnd = { finalOffset ->
                            if (finalOffset.x > screenWidth - panelWidth - 40f) {
                                activeItems = activeItems.filter { it.key != item.key }
                            } else {
                                val updatedItems = activeItems.map {
                                    if (it.key == item.key) it.copy(offset = finalOffset) else it
                                }
                                checkCombinations(item.key, finalOffset, updatedItems) { combinedItems ->
                                    activeItems = combinedItems
                                    combinedItems.forEach { newItem ->
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

            // Side Panel on the right
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(100.dp)
                    .align(Alignment.CenterEnd)
                    .onGloballyPositioned { panelWidth = it.size.width.toFloat() },
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    items(availableItems.toList(), key = { it }) { id ->
                        val item = Alchemist.items.find { it.id == id }
                        var itemY by remember { mutableStateOf(0f) }
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .onGloballyPositioned { itemY = it.positionInRoot().y }
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = { startOffset ->
                                            val key = System.nanoTime()
                                            val panelLeft = screenWidth - panelWidth
                                            // Finger at center: 72dp size means 36dp offset. 
                                            // Root pos is itemY + startOffset.y, so we subtract 36dp
                                            val initialPos = Offset(panelLeft + startOffset.x - 36f, itemY + startOffset.y - 36f)
                                            activeItems = activeItems + PlacedItem(id, initialPos, key)
                                            currentDraggingKey = key
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            currentDraggingKey?.let { key ->
                                                activeItems = activeItems.map {
                                                    if (it.key == key) it.copy(offset = it.offset + dragAmount) else it
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            currentDraggingKey?.let { key ->
                                                val draggedItem = activeItems.find { it.key == key }
                                                if (draggedItem != null) {
                                                    if (draggedItem.offset.x > screenWidth - panelWidth - 40f) {
                                                        activeItems = activeItems.filter { it.key != key }
                                                    } else {
                                                        checkCombinations(key, draggedItem.offset, activeItems) { combinedItems ->
                                                            activeItems = combinedItems
                                                            combinedItems.forEach { newItem ->
                                                                if (newItem.id !in availableItems) {
                                                                    ds.addStringToSet("available_items", newItem.id.toString())
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            currentDraggingKey = null
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
                                ElementIcon(id)
                            }
                            Text(item?.name ?: "", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            
            // Context Menu
            if (contextMenuExpanded) {
                DropdownMenu(
                    expanded = contextMenuExpanded,
                    onDismissRequest = { contextMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("See details") },
                        onClick = {
                            contextMenuExpanded = false
                            contextMenuElementId?.let { 
                                backStack.add(Route.ItemDetails(it.toInt())) 
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ElementIcon(id: Long) {
    val context = LocalContext.current
    val bitmap = remember(id) {
        try {
            val inputStream = context.assets.open("$id.png")
            BitmapFactory.decodeStream(inputStream).asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(bitmap, null, Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize().background(Color.Gray))
    }
}

@Composable
fun DraggableElement(
    id: Long,
    initialOffset: Offset,
    onDragEnd: (Offset) -> Unit,
    onLongClick: () -> Unit
) {
    var offset by remember(initialOffset) { mutableStateOf(initialOffset) }

    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(72.dp)
            .combinedClickable(
                onLongClick = onLongClick,
                onClick = {}
            )
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd(offset) }
                ) { change, dragAmount ->
                    change.consume()
                    offset += dragAmount
                }
            }
    ) {
        ElementIcon(id)
    }
}

fun checkCombinations(
    movedKey: Long,
    movedOffset: Offset,
    currentItems: List<PlacedItem>,
    onCombined: (List<PlacedItem>) -> Unit
) {
    val movedItem = currentItems.find { it.key == movedKey } ?: return
    val others = currentItems.filter { it.key != movedKey }

    val target = others.find { other ->
        val dist = (other.offset - movedOffset).getDistance()
        dist < 60f
    }

    if (target != null) {
        val recipe = Alchemist.recipes.find {
            it.inputs.size == 2 &&
            it.inputs.contains(movedItem.id) &&
            it.inputs.contains(target.id)
        }

        if (recipe != null) {
            val remaining = currentItems.filter { it.key != movedKey && it.key != target.key }
            val newItems = remaining + recipe.outputs.map {
                PlacedItem(it, target.offset)
            }
            onCombined(newItems)
        } else {
            onCombined(currentItems)
        }
    } else {
        onCombined(currentItems)
    }
}
