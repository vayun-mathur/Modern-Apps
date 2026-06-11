package com.vayunmathur.games.alchemist.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vayunmathur.games.alchemist.R
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.games.alchemist.util.AlchemistViewModel
import com.vayunmathur.games.alchemist.util.PlacedItem
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.roundToInt

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

    // --- UI-only filter state (per the plan: dialog/filter visibility stays in compose) ---
    var activeCustomFilter by remember { mutableStateOf<String?>(null) }
    var activeFilter by remember { mutableStateOf<String?>(null) }
    val displayItems by remember(availableItems, activeFilter, activeCustomFilter) {
        derivedStateOf {
            availableItems.filter { item ->
                val filterToApply = activeCustomFilter ?: activeFilter
                when (filterToApply) {
                    "A - F" -> item.name.firstOrNull()?.let { it in 'A'..'F' } ?: false
                    "G - J" -> item.name.firstOrNull()?.let { it in 'G'..'J' } ?: false
                    "K - Z" -> item.name.firstOrNull()?.let { it in 'K'..'Z' } ?: false
                    null -> true
                    else -> item.name.firstOrNull()?.let { it.uppercaseChar().toString() == filterToApply } ?: false
                }
            }
        }
    }

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
                IconButton(onClick = onOpenGameCenter) {
                    Icon(
                        painterResource(id = android.R.drawable.btn_star_big_on), "Achievements"
                    )
                }
                com.vayunmathur.library.ui.BackupButtons(
                    datastoreNames = listOf("datastore_default")
                )
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
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 2.1 FILTER CHIPS
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val filters = listOf("A - F", "G - J", "K - Z")
                        filters.forEach { filter ->
                            FilterChip(
                                selected = activeFilter == filter && activeCustomFilter == null,
                                onClick = {
                                    activeCustomFilter = null
                                    activeFilter = if (activeFilter == filter) null else filter
                                },
                                label = { Text(filter) }
                            )
                        }
                        InputFilterChip(
                            selected = activeCustomFilter != null,
                            value = activeCustomFilter ?: "",
                            onValueChange = { activeFilter = null; activeCustomFilter = it }
                        )
                    }

                    // 2.2 INVENTORY COUNT (discovered / total)
                    Text(
                        stringResource(
                            R.string.counter, availableItems.size, allItems.size
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
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
                                items(displayItems, key = { it.id }) { item ->
                                    var itemPosInWindow by remember { mutableStateOf(Offset.Zero) }

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .onGloballyPositioned {
                                                itemPosInWindow = it.positionInWindow()
                                            }
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
                                                    // Final Check: Drop it if it's clear of the bottom bar
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
                                            }) {
                                        Box(
                                            Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .combinedClickable(onLongClick = {
                                                    contextMenuElementId = item.id
                                                    contextMenuExpanded = true
                                                }, onClick = {})
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
fun InputFilterChip(selected: Boolean, value: String, onValueChange: (String?) -> Unit) {
    var isEditing by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    FilterChip(
        selected = selected,
        onClick = {
            if (selected) onValueChange(null) else isEditing = true
        },
        label = {
            Box {
                if (isEditing) {
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                    BasicTextField(
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                        value = value,
                        keyboardOptions = KeyboardOptions.Default.copy(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                if (value.trim().isEmpty()) {
                                    onValueChange(null)
                                } else {
                                    val lastChar = value.last()
                                    if (lastChar.isLetter()) {
                                        val letter = lastChar.uppercaseChar().toString()
                                        onValueChange(letter)
                                    }
                                }
                                isEditing = false
                            }
                        ),
                        onValueChange = { newValue ->
                            if (newValue.trim().isEmpty()) {
                                onValueChange(null)
                                isEditing = false
                            } else {
                                val lastChar = newValue.last()
                                if (lastChar.isLetter()) {
                                    val letter = lastChar.uppercaseChar().toString()
                                    onValueChange(letter)
                                    isEditing = false
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                } else {
                    Text(if (value.isEmpty()) "Custom" else value.uppercase())
                }
            }
        }
    )
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
