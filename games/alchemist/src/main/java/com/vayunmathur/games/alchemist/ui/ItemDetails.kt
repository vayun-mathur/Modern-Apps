package com.vayunmathur.games.alchemist.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.games.alchemist.Route
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsScreen(backStack: NavBackStack<Route>, ds: DataStoreUtils, itemId: Int) {
    Scaffold(topBar = {
        TopAppBar({Text("Item Details")}, navigationIcon = { IconNavigation(backStack) })
    }) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {

        }
    }
}