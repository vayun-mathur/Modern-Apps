package com.vayunmathur.astronomy.ui.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vayunmathur.astronomy.Route
import com.vayunmathur.astronomy.ui.AstronomyViewModel
import com.vayunmathur.library.ui.*
import com.vayunmathur.library.util.NavBackStack

@Composable
fun SearchPage(backStack: NavBackStack<Route>, viewModel: AstronomyViewModel) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(viewModel.search("")) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Search") }, navigationIcon = { IconNavigation(backStack) })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            OutlinedTextField(value = query, onValueChange = { query = it; results = viewModel.search(it) }, label = { Text("Search stars, planets, Messier") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results) { r ->
                    ListItem(
                        headlineContent = { Text(r.title) },
                        supportingContent = { Text(r.subtitle) },
                        trailingContent = { IconChevronRight() },
                        modifier = Modifier.clickable {
                            if (!r.id.startsWith("CONST_")) {
                                viewModel.selectObject(r.id)
                                // Drop the search page from the stack so back from the
                                // detail page returns straight to the sky map.
                                backStack.pop()
                                backStack.add(Route.ObjectDetail(r.id))
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
