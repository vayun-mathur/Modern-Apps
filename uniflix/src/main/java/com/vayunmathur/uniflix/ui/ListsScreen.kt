package com.vayunmathur.uniflix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.uniflix.domain.MediaItem
import com.vayunmathur.uniflix.domain.MediaType
import com.vayunmathur.uniflix.platform.ListsViewModel

/** Local user lists hub: create lists, file titles, remove. */
@Composable
fun ListsScreen(onBack: () -> Unit, onOpenDetail: (MediaItem) -> Unit) {
    val vm: ListsViewModel = viewModel()
    val lists by vm.lists.collectAsState(initial = emptyList())
    var draft by remember { mutableStateOf("") }

    AppScaffold(
        title = "Lists",
        onNavigateBack = onBack,
    ) { padding ->
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("New list") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Button(onClick = {
                        vm.createList(draft)
                        draft = ""
                    }, enabled = draft.isNotBlank()) { Text("Add") }
                }
                lists.forEach { list ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(list.name, style = MaterialTheme.typography.titleMedium)
                                IconButton(onClick = { vm.deleteList(list) }) { IconDelete() }
                            }
                            val items by vm.items(list.id).collectAsState(initial = emptyList())
                            items.forEach { item ->
                                val mediaItem = MediaItem(
                                    tmdbId = item.tmdbId,
                                    mediaType = if (item.mediaType == "tv") MediaType.TV else MediaType.MOVIE,
                                    title = item.title,
                                    posterPath = item.posterPath,
                                )
                                MediaCard(
                                    item = mediaItem,
                                    onOpen = {
                                        onOpenDetail(mediaItem)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                IconButton(onClick = { vm.deleteItem(item) }) { IconDelete() }
                            }
                            if (items.isEmpty()) {
                                Text("Empty — add titles from a detail page.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
