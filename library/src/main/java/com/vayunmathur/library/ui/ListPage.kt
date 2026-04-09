package com.vayunmathur.library.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.R
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.ReorderableDatabaseItem
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : DatabaseItem, Route : NavKey, reified EditPage : Route> ListPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: suspend (id: Long) -> Route,
    noinline editPage: (() -> Route)? = null,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    crossinline leadingContent: @Composable (T) -> Unit = {},
    crossinline trailingContent: @Composable (T) -> Unit = {},
    searchEnabled: Boolean = false,
    sortOrder: Comparator<T>? = null,
    crossinline searchString: (T) -> String = {it.toString()},
    noinline bottomBar: @Composable () -> Unit = {},
    noinline fab: (@Composable () -> Unit)? = null
) {
    val dbDataUnfiltered by viewModel.data<T>().collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val dbData by remember { derivedStateOf {
        val filtered = dbDataUnfiltered.filter { searchQuery.isBlank() || searchString(it).contains(searchQuery, true) }
        if(sortOrder != null) filtered.sortedWith(sortOrder) else filtered
    } }

    // 1. Initialize the reorderable state
    val listState = rememberLazyListState()

    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, actions = {
                otherActions()
                settingsPage?.let { settingsPage ->
                    IconButton(onClick = { backStack.add(settingsPage) }) {
                        IconSettings()
                    }
                }
            })
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column {
                fab?.invoke()
                if (editPage != null && backStack.last() !is EditPage) {
                    FloatingActionButton(onClick = { backStack.add(editPage()) }) {
                        IconAdd()
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Only apply top padding here to keep Search Bar below TopAppBar
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            if (searchEnabled) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = { IconSearch() }
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Apply the remaining Scaffold padding here
                contentPadding = PaddingValues(
                    start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                    end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                    bottom = paddingValues.calculateBottomPadding()
                )
            ) {
                items(dbData, key = { it.id }) { item ->
                    ListItem({ headlineContent(item) }, Modifier.clickable {
                        coroutineScope.launch {
                            backStack.add(viewPage(item.id))
                        }
                    }, {}, { supportingContent(item) }, {leadingContent(item)}, {
                        Row {
                            trailingContent(item)
                        }
                    }, ListItemDefaults.colors())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : ReorderableDatabaseItem<T>, Route : NavKey, reified EditPage : Route> ListPageR(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: (id: Long) -> Route,
    crossinline editPage: () -> Route,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    crossinline leadingContent: @Composable (T) -> Unit = {},
    crossinline trailingContent: @Composable (T) -> Unit = {},
    searchEnabled: Boolean = false,
    crossinline searchString: (T) -> String = {it.toString()},
) {
    val dbDataUnfiltered by viewModel.data<T>().collectAsState(listOf())
    var searchQuery by remember { mutableStateOf("") }
    val dbData by remember { derivedStateOf { dbDataUnfiltered.filter { searchQuery.isBlank() || searchString(it).contains(searchQuery, true) } } }

    val hapticFeedback = LocalHapticFeedback.current

    // 1. Initialize the reorderable state
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(dbData) }

    val state = rememberReorderableLazyListState(listState, onMove = { from, to ->
        // 1. Normalize UI indices to 0-based Data indices
        // If search is on, UI says "1" but we need "0".
        val fromIdx = if (searchEnabled) from.index - 1 else from.index
        val toIdx = if (searchEnabled) to.index - 1 else to.index

        // 2. Safety Bounds Check
        if (fromIdx in localData.indices && toIdx in localData.indices) {

            // 3. Find the neighbors to calculate the new fractional position.
            // If moving DOWN: target is at toIdx, neighbor above is also toIdx (since item will drop AFTER it).
            // If moving UP: target is at toIdx, neighbor above is toIdx - 1.
            val prevIdx = if (toIdx > fromIdx) toIdx else toIdx - 1
            val nextIdx = if (toIdx > fromIdx) toIdx + 1 else toIdx

            val prevPos = localData.getOrNull(prevIdx)?.position
            val nextPos = localData.getOrNull(nextIdx)?.position

            // 4. Calculate the midpoint
            val resultItemPosition = when {
                prevPos == null -> (nextPos ?: 0.0) - 50.0
                nextPos == null -> prevPos + 50.0
                else -> (prevPos + nextPos) / 2.0
            }

            // 5. Atomic Update: Create new list, move item, and update state
            val mutableList = localData.toMutableList()
            val movedItem = mutableList.removeAt(fromIdx).withPosition(resultItemPosition)
            mutableList.add(toIdx, movedItem)

            localData = mutableList

            hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }
    })

    // Keep localData in sync with DB updates, but NOT while dragging
    LaunchedEffect(dbData) {
        if (!state.isAnyItemDragging) {
            localData = dbData.sortedBy { it.position }
        }
    }

    val isDragging = state.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && localData != dbData) {
            // Find the changes and update the DB once
            viewModel.upsertAll<T>(localData)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, actions = {
                otherActions()
                settingsPage?.let { settingsPage ->
                    IconButton(onClick = { backStack.add(settingsPage) }) {
                        IconSettings()
                    }
                }
            })
        },
        floatingActionButton = {
            if (backStack.last() !is EditPage) {
                FloatingActionButton(onClick = { backStack.add(editPage()) }) {
                    IconAdd()
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Only apply top padding here to keep Search Bar below TopAppBar
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            if (searchEnabled) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    leadingIcon = { IconSearch() }
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Apply the remaining Scaffold padding here
                contentPadding = PaddingValues(
                    start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                    end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                    bottom = paddingValues.calculateBottomPadding()
                )
            ) {
                items(localData, key = { it.id }) { item ->
                    // 3. Wrap each item in ReorderableItem
                    ReorderableItem(state, key = item.id) { isDragging ->

                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                        Surface(Modifier.animateItem(), shadowElevation = elevation) {
                            ListItem({ headlineContent(item) }, Modifier.clickable {
                                backStack.add(viewPage(item.id))
                            }, {}, { supportingContent(item) }, { leadingContent(item) }, {
                                Row {
                                    trailingContent(item)
                                    IconButton(
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                hapticFeedback.performHapticFeedback(
                                                    HapticFeedbackType.GestureThresholdActivate
                                                )
                                            },
                                            onDragStopped = {
                                                hapticFeedback.performHapticFeedback(
                                                    HapticFeedbackType.GestureEnd
                                                )
                                            },
                                        ),
                                        onClick = {},
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.drag_handle_24px),
                                            contentDescription = "Reorder"
                                        )
                                    }
                                }
                            }, ListItemDefaults.colors(), elevation, elevation)
                        }
                    }
                }
            }
        }
    }
}