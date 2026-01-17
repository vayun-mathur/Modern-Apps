package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.vayunmathur.library.util.DatabaseItem
import com.vayunmathur.library.util.DatabaseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
inline fun <reified T : DatabaseItem, Route: NavKey, reified EditPage: Route> ListPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel,
    title: String,
    crossinline listItem: @Composable (item: T, onClick: () -> Unit) -> Unit,
    crossinline viewPage: (id: Long) -> Route,
    crossinline editPage: () -> Route,
    settingsPage: Route? = null,
    crossinline otherActions: @Composable () -> Unit = {}
) {
    val passwords by viewModel.data<T>().collectAsState()

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
            if(backStack.last() !is EditPage) {
                FloatingActionButton(onClick = {
                    backStack.add(editPage())
                }) {
                    IconAdd()
                }
            }
        },
        contentWindowInsets = WindowInsets()
    ) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues).padding(horizontal = 8.dp)) {
            items(passwords, key = { it.id }) { item ->
                listItem(item) {
                    backStack.add(viewPage(item.id))
                }
            }
        }
    }
}