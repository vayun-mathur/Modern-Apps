package com.vayunmathur.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : DatabaseItem, Route : NavKey, reified EditPage : Route> ListPage(
    backStack: NavBackStack<Route>,
    data: List<T>,
    title: String,
    crossinline headlineContent: @Composable (T) -> Unit,
    crossinline supportingContent: @Composable (T) -> Unit,
    crossinline viewPage: suspend (id: Long) -> Route,
    noinline editPage: (() -> Route)? = null,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {},
    crossinline leadingContent: @Composable (T) -> Unit = {},
    crossinline trailingContent: @Composable (T) -> Unit = {},
    noinline itemModifier: @Composable (T) -> Modifier = { Modifier },
    noinline itemColors: @Composable (T) -> ListItemColors = { ListItemDefaults.colors() },
    searchEnabled: Boolean = false,
    sortOrder: Comparator<T>? = null,
    crossinline searchString: (T) -> String = { it.toString() },
    noinline bottomBar: @Composable () -> Unit = {},
    noinline fab: (@Composable () -> Unit)? = null,
) {
    var searchQuery by remember { mutableStateOf("") }
    val displayed = remember(data, searchQuery, sortOrder) {
        val filtered = if (searchQuery.isBlank()) data
        else data.filter { searchString(it).contains(searchQuery, true) }
        if (sortOrder != null) filtered.sortedWith(sortOrder) else filtered
    }

    BackHandler(enabled = searchEnabled && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchEnabled) {
                        CommonSearchBar(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = title,
                            padding = PaddingValues(0.dp)
                        )
                    } else {
                        Text(title)
                    }
                },
                actions = {
                    otherActions()
                    settingsPage?.let { settingsPage ->
                        IconButton(onClick = { backStack.add(settingsPage) }) {
                            IconSettings()
                        }
                    }
                }
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column {
                fab?.invoke()
                if (editPage != null && backStack.backStack.lastOrNull() !is EditPage) {
                    FloatingActionButton(onClick = { backStack.add(editPage()) }) {
                        IconAdd()
                    }
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(displayed, key = { it.id }) { item ->
                val modifier = itemModifier(item)
                ListItem(
                    modifier = modifier.clickable {
                        coroutineScope.launch {
                            backStack.add(viewPage(item.id))
                        }
                    },
                    overlineContent = {},
                    supportingContent = { supportingContent(item) },
                    leadingContent = { leadingContent(item) },
                    trailingContent = {
                        Row {
                            trailingContent(item)
                        }
                    },
                    colors = itemColors(item),
                ) { headlineContent(item) }
            }
        }
    }
}
