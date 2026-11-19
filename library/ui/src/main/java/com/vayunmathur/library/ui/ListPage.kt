package com.vayunmathur.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.dropsAway
import kotlinx.coroutines.launch

/**
 * A searchable, sortable list screen with the standard bar, FAB and row layout.
 *
 * Only [EditPage] needs reifying - to recognise the edit destination on the back stack - so this
 * is a thin inline shell that lowers it to a predicate and hands everything else to
 * [ListPageContent]. Keeping the body out of here is deliberate: an `inline` composable has no
 * restart scope, so if the `Scaffold` and `LazyColumn` lived here they would be inlined into the
 * caller and recompose whenever *anything* the caller reads changes - including the search field's
 * own state, which lives inside this function. That turned one keystroke into a full re-filter,
 * re-sort and re-composition of the whole list.
 *
 * [sortOrder] must be a stable instance. A `Comparator` built inline at the call site is a new
 * object on every recomposition, which invalidates the memoised sort and re-sorts the entire list;
 * wrap it in `remember { }`.
 */
@Composable
inline fun <T : DatabaseItem, Route : NavKey, reified EditPage : Route> ListPage(
    backStack: NavBackStack<Route>,
    data: List<T>,
    title: String,
    noinline headlineContent: @Composable (T) -> Unit,
    noinline supportingContent: @Composable (T) -> Unit,
    noinline viewPage: suspend (id: Long) -> Route,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    noinline editPage: (() -> Route)? = null,
    settingsPage: Route? = null,
    noinline otherActions: @Composable () -> Unit = {},
    noinline leadingContent: @Composable (T) -> Unit = {},
    noinline trailingContent: @Composable (T) -> Unit = {},
    noinline itemModifier: @Composable (T) -> Modifier = { Modifier },
    noinline itemColors: @Composable (T) -> ListItemColors = { ListItemDefaults.colors() },
    searchEnabled: Boolean = false,
    sortOrder: Comparator<T>? = null,
    noinline searchString: (T) -> String = { it.toString() },
    noinline bottomBar: @Composable () -> Unit = {},
    noinline fab: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
) = ListPageContent(
    backStack = backStack,
    data = data,
    title = title,
    headlineContent = headlineContent,
    supportingContent = supportingContent,
    viewPage = viewPage,
    scrollBehavior = scrollBehavior,
    editPage = editPage,
    settingsPage = settingsPage,
    otherActions = otherActions,
    leadingContent = leadingContent,
    trailingContent = trailingContent,
    itemModifier = itemModifier,
    itemColors = itemColors,
    searchEnabled = searchEnabled,
    sortOrder = sortOrder,
    searchString = searchString,
    bottomBar = bottomBar,
    fab = fab,
    loading = loading,
    isEditPage = { it is EditPage },
)

/**
 * The body of [ListPage]. Not inline, so it owns a restart scope and the search field's state, and
 * skips when its caller recomposes for unrelated reasons.
 *
 * Internal - it exists only because [ListPage] has to stay inline to reify its edit route, and a
 * public inline function cannot call a private one.
 */
@PublishedApi
@Composable
internal fun <T : DatabaseItem, Route : NavKey> ListPageContent(
    backStack: NavBackStack<Route>,
    data: List<T>,
    title: String,
    headlineContent: @Composable (T) -> Unit,
    supportingContent: @Composable (T) -> Unit,
    viewPage: suspend (id: Long) -> Route,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    isEditPage: (Any?) -> Boolean,
    editPage: (() -> Route)? = null,
    settingsPage: Route? = null,
    otherActions: @Composable () -> Unit = {},
    leadingContent: @Composable (T) -> Unit = {},
    trailingContent: @Composable (T) -> Unit = {},
    itemModifier: @Composable (T) -> Modifier = { Modifier },
    itemColors: @Composable (T) -> ListItemColors = { ListItemDefaults.colors() },
    searchEnabled: Boolean = false,
    sortOrder: Comparator<T>? = null,
    searchString: (T) -> String = { it.toString() },
    bottomBar: @Composable () -> Unit = {},
    fab: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
) {
    var searchQuery by remember { mutableStateOf("") }
    // Built once per data change, not once per keystroke. `searchString` concatenates several
    // fields, and doing that (plus a case-insensitive `contains`, which lowercases as it goes) for
    // every row on every keypress is a whole-list pass between two frames of typing.
    val haystacks = remember(data, searchString) {
        if (!searchEnabled) emptyList() else data.map { searchString(it).lowercase() }
    }
    val displayed = remember(data, haystacks, searchQuery, sortOrder) {
        val query = searchQuery.trim().lowercase()
        val filtered =
            if (query.isEmpty()) data
            else data.filterIndexed { index, _ -> haystacks[index].contains(query) }
        if (sortOrder != null) filtered.sortedWith(sortOrder) else filtered
    }

    BackHandler(enabled = searchEnabled && searchQuery.isNotEmpty()) {
        searchQuery = ""
    }

    val behavior = scrollBehavior ?: appBarScrollBehavior()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection),
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
                },
                scrollBehavior = behavior,
            )
        },
        bottomBar = bottomBar,
        floatingActionButton = {
            Column {
                fab?.invoke()
                if (editPage != null && !isEditPage(backStack.backStack.lastOrNull())) {
                    FloatingActionButton(
                        onClick = { backStack.add(editPage()) },
                        modifier = Modifier.dropsAway(),
                    ) {
                        IconAdd()
                    }
                }
            }
        }
    ) { paddingValues ->
        // An empty list and a list that has not arrived yet are both "no items", and rendering
        // them the same way means a slow first load reads as an empty vault/library rather than
        // as work in progress.
        if (loading && displayed.isEmpty()) {
            LoadingState(Modifier.fillMaxSize().padding(paddingValues))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues
        ) {
            items(displayed, key = { it.id }, contentType = { it::class }) { item ->
                val modifier = itemModifier(item)
                ListItem(
                    modifier = modifier.clickable {
                        coroutineScope.launch {
                            backStack.add(viewPage(item.id))
                        }
                    },
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
