package com.vayunmathur.todo.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconMenu
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarScrollBehavior
import com.vayunmathur.todo.R
import com.vayunmathur.todo.domain.SortMode

/** Search field doubling as the view title, the drawer button, and the sort menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoListTopBar(
    title: String,
    query: String,
    sort: SortMode,
    onQuery: (String) -> Unit,
    onSort: (SortMode) -> Unit,
    onMenu: (() -> Unit)?,
    scrollBehavior: TopAppBarScrollBehavior?,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        navigationIcon = { onMenu?.let { IconButton(onClick = it) { IconMenu() } } },
        title = {
            CommonSearchBar(
                value = query,
                onValueChange = onQuery,
                placeholder = stringResource(R.string.search_in, title),
                padding = PaddingValues(0.dp),
            )
        },
        actions = {
            SortMenu(sort, onSort)
            extraActions()
        },
        scrollBehavior = scrollBehavior,
    )
}
