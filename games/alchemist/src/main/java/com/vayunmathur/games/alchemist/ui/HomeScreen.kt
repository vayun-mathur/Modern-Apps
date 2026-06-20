package com.vayunmathur.games.alchemist.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.util.AlchemistViewModel
import com.vayunmathur.games.alchemist.util.PlacedItem
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    backStack: NavBackStack<Route>,
    viewModel: AlchemistViewModel,
    onOpenGameCenter: () -> Unit
) {
    val availableItems by viewModel.availableItems.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val activeItems by viewModel.placedElements.collectAsState()

    val scope = rememberCoroutineScope()

    var bottomBarTopInWindow by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var playAreaOffsetInWindow by remember { mutableStateOf(Offset.Zero) }
    var isDraggingBoardItem by remember { mutableStateOf(false) }

    // Tracking for the current item being "pulled out" of the bottom bar
    var draggingInventoryId by remember { mutableStateOf<Long?>(null) }
    var draggingInventoryOffset by remember { mutableStateOf(Offset.Zero) }

    var contextMenuElementId by remember { mutableStateOf<Long?>(null) }
    var contextMenuExpanded by remember { mutableStateOf(false) }

    val lazyList = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                if (activeItems.isNotEmpty()) {
                    IconButton(onClick = { viewModel.clearElements() }) {
                        Icon(
                            painterResource(id = android.R.drawable.ic_menu_close_clear_cancel), "Clear"
                        )
                    }
                }
                IconButton(onClick = onOpenGameCenter) {
                    Icon(
                        painterResource(id = android.R.drawable.btn_star_big_on), "Achievements"
                    )
                }
            })
        }) { paddingValues ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 1. PLAY AREA (Full Screen)
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(if (isDraggingBoardItem) 1f else 0f)
                    .onGloballyPositioned {
                        playAreaOffsetInWindow = it.positionInWindow()
                    }) {
                activeItems.forEach { item ->
                    key(item.key) {
                        DraggableElement(item = item, onDragStart = {
                            isDraggingBoardItem = true
                        }, onDragEnd = { finalOffset ->
                            isDraggingBoardItem = false
                            val limitY = bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                            // DELETION: Triggered if any part of the item touches the bottom bar
                            if (finalOffset.y > limitY) {
                                viewModel.removeElement(item.key)
                            } else {
                                viewModel.updateElementPosition(item.key, finalOffset)
                                viewModel.tryCombine(item.key, finalOffset)
                            }
                        }, onLongClick = {
                            contextMenuElementId = item.id
                            contextMenuExpanded = true
                        }, onDoubleTap = {
                            viewModel.duplicateElement(item.key)
                        })
                    }
                }
            }

            // 2. BOTTOM PANEL OVERLAY
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
                    .height(192.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // 2.1 INVENTORY COUNT (discovered / total)
                Text(
                    stringResource(
                        R.string.counter, availableItems.size, allItems.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                // 2.2 A-Z LETTER BAR
                val activeLetters = remember(availableItems) {
                    availableItems.mapNotNull { it.name.firstOrNull()?.uppercaseChar() }.toSet()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ('A'..'Z').filter { it in activeLetters }.forEach { letter ->
                        Text(
                            text = letter.toString(),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    val index = availableItems.indexOfFirst {
                                        it.name.firstOrNull()?.uppercaseChar() == letter
                                    }
                                    if (index >= 0) {
                                        scope.launch { lazyList.animateScrollToItem(index) }
                                    }
                                }
                                .padding(horizontal = 2.dp, vertical = 4.dp)
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            bottomBarTopInWindow = it.positionInWindow().y
                        },
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                ) {
                    Crossfade(
                        targetState = isDraggingBoardItem,
                        label = "bottom_bar_crossfade"
                    ) { isDragging ->
                        if (isDragging) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painterResource(id = android.R.drawable.ic_delete),
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        } else {
                            LazyRow(
                                state = lazyList,
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(availableItems, key = { it.id }) { item ->
                                    var itemPosInWindow by remember { mutableStateOf(Offset.Zero) }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .onGloballyPositioned {
                                                itemPosInWindow = it.positionInWindow()
                                            }) {
                                        Box(
                                            Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickable(onLongClick = {
                                                    contextMenuElementId = item.id
                                                    contextMenuExpanded = true
                                                }, onClick = {})
                                                .pointerInput(item.id) {
                                                    detectDragGestures(onDragStart = { startOffset ->
                                                        draggingInventoryId = item.id
                                                        val fingerInWindow =
                                                            itemPosInWindow + startOffset
                                                        draggingInventoryOffset = Offset(
                                                            x = fingerInWindow.x - playAreaOffsetInWindow.x - 100f,
                                                            y = fingerInWindow.y - playAreaOffsetInWindow.y - 100f
                                                        )
                                                    }, onDrag = { change, dragAmount ->
                                                        change.consume()
                                                        draggingInventoryOffset += dragAmount
                                                    }, onDragEnd = {
                                                        val limitY =
                                                            bottomBarTopInWindow - playAreaOffsetInWindow.y - 48f
                                                        if (draggingInventoryOffset.y < limitY) {
                                                            viewModel.placeElement(
                                                                item.id, draggingInventoryOffset
                                                            )
                                                        }
                                                        draggingInventoryId = null
                                                    }, onDragCancel = {
                                                        draggingInventoryId = null
                                                    })
                                                }
                                        ) {
                                            DynamicAlchemyIcon(item.id)
                                            if (item.final) {
                                                Icon(
                                                    painterResource(id = android.R.drawable.star_on),
                                                    contentDescription = "Final Item",
                                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .align(Alignment.BottomEnd)
                                                )
                                            }
                                        }
                                        Text(item.name, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. GLOBAL DRAG OVERLAY (item being pulled out of the bottom bar)
            draggingInventoryId?.let { id ->
                Box(Modifier
                    .offset {
                        IntOffset(
                            draggingInventoryOffset.x.roundToInt(),
                            draggingInventoryOffset.y.roundToInt()
                        )
                    }
                    .size(72.dp)
                ) { DynamicAlchemyIcon(id) }
            }
        }

        if (contextMenuExpanded) {
            DropdownMenu(
                expanded = contextMenuExpanded,
                onDismissRequest = { contextMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.see_details)) }, onClick = {
                    contextMenuExpanded = false
                    contextMenuElementId?.let {
                        backStack.add(Route.ItemDetails(it.toInt()))
                    }
                })
            }
        }
    }
}

@Composable
fun DraggableElement(
    item: PlacedItem,
    onDragStart: () -> Unit,
    onDragEnd: (Offset) -> Unit,
    onLongClick: () -> Unit,
    onDoubleTap: () -> Unit
) {
    var currentOffset by remember(item.key) { mutableStateOf(item.offset) }

    Box(
        Modifier
            .offset {
                IntOffset(currentOffset.x.roundToInt(), currentOffset.y.roundToInt())
            }
            .size(72.dp)
            .combinedClickable(onLongClick = onLongClick, onClick = {})
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = { onDoubleTap() })
            }
            .pointerInput(item.key) {
                detectDragGestures(
                    onDragStart = { _ -> onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOffset += dragAmount
                    },
                    onDragEnd = { onDragEnd(currentOffset) },
                    onDragCancel = { onDragEnd(currentOffset) }
                )
            }) { DynamicAlchemyIcon(item.id) }
}
