package com.vayunmathur.library.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.R
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseViewModel
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : DatabaseItem<T>, Route : NavKey, reified EditPage : Route> ListPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: (id: Long) -> Route,
    crossinline editPage: () -> Route,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    isReorderable: Boolean = false,
    crossinline trailingContent: @Composable (T) -> Unit = {},
    searchEnabled: Boolean = false,
    crossinline searchString: (T) -> String = {it.toString()},
) {
    val dbDataUnfiltered by viewModel.data<T>().collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    val dbData by remember { derivedStateOf { dbDataUnfiltered.filter { searchQuery.isBlank() || searchString(it).contains(searchQuery, true) } } }

    val hapticFeedback = LocalHapticFeedback.current

    // 1. Initialize the reorderable state
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(dbData) }

    val state = rememberReorderableLazyListState(listState, onMove = { from, to ->
        // 2. ONLY update the local shadow list during the drag
        val toReal = if(to.index > from.index) to.index else to.index-1
        val toItemPosition = localData.getOrNull(toReal)?.position ?: (localData[0].position - 50.0)
        val nextItemPosition = localData.getOrNull(toReal + 1)?.position ?: (toItemPosition + 50.0)
        val resultItemPosition = (toItemPosition+nextItemPosition)/2
        localData = localData.toMutableList().apply {
            set(from.index, localData[from.index].withPosition(resultItemPosition))
            add(to.index, removeAt(from.index))
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    })

    // Keep localData in sync with DB updates, but NOT while dragging
    LaunchedEffect(dbData) {
        if (!state.isAnyItemDragging) {
            localData = dbData
        }
    }

    val isDragging = state.isAnyItemDragging
    LaunchedEffect(isDragging) {
        if (!isDragging && localData != dbData) {
            println(localData)
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
        },
        contentWindowInsets = WindowInsets(0)
    ) { paddingValues ->
        // 2. Apply reorderable modifier to the LazyColumn
        Column(Modifier.padding(paddingValues)) {
            if(searchEnabled) {
                OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, leadingIcon = {
                    IconSearch()
                })
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(localData, key = { it.id }) { item ->
                    // 3. Wrap each item in ReorderableItem
                    ReorderableItem(state, key = item.id) { isDragging ->

                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp)

                        Surface(Modifier.animateItem(), shadowElevation = elevation) {
                            ListItem({ headlineContent(item) }, Modifier.clickable {
                                backStack.add(viewPage(item.id))
                            }, {}, { supportingContent(item) }, {}, {
                                Row {
                                    trailingContent(item)
                                    if (isReorderable) {
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
                                }
                            }, ListItemDefaults.colors(), elevation, elevation)
                        }
                    }
                }
            }
        }
    }
}