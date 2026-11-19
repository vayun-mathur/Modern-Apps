package com.vayunmathur.maps.ui
import com.vayunmathur.maps.data.string

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.maps.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.maps.Route
import com.vayunmathur.maps.data.SavedPlace
import com.vayunmathur.maps.platform.SavedPlacesViewModel

/**
 * Saved-places screen (P6): view / edit / remove the user's saved places. The
 * flat starred list (rename, add-to-list, remove), and named lists (create,
 * delete, remove members). Built from the shared `library/ui` settings
 * components (no raw Scaffold); all edits go through [SavedPlacesViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPlacesPage(backStack: NavBackStack<Route>, viewModel: SavedPlacesViewModel) {
    val saved by viewModel.saved.collectAsState()
    val lists by viewModel.lists.collectAsState()

    var renameTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var addToListTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var showCreateList by remember { mutableStateOf(false) }

    AppScaffold(
        title = stringResource(R.string.saved_places_title),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // --- Flat saved list ---
            SettingsSection(title = stringResource(UiR.string.saved)) {
                if (saved.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.saved_list_empty),
                        enabled = false,
                    )
                }
                saved.forEach { place ->
                    SavedPlaceRow(
                        place = place,
                        onRename = { renameTarget = place },
                        onAddToList = { addToListTarget = place },
                        onRemove = { viewModel.removeSaved(place) },
                    )
                }
            }

            // --- Named lists ---
            SettingsSection(title = stringResource(R.string.saved_lists_header)) {
                SettingsRow(
                    title = stringResource(R.string.saved_lists_new),
                    leadingContent = { IconAdd() },
                    onClick = { showCreateList = true },
                )
                lists.forEach { (name, places) ->
                    SettingsRow(
                        title = name,
                        supportingText = androidx.compose.ui.res.pluralStringResource(
                            R.plurals.saved_list_count, places.size, places.size
                        ),
                        leadingContent = { IconList() },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteList(name) }) { IconDelete() }
                        },
                    )
                    places.forEach { place ->
                        SettingsRow(
                            title = place.name,
                            modifier = Modifier.padding(start = 16.dp),
                            leadingContent = { IconStar() },
                            trailingContent = {
                                IconButton(onClick = { viewModel.removeFromList(name, place) }) { IconClose() }
                            },
                        )
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        TextEntryDialog(
            title = stringResource(R.string.saved_rename_title),
            initial = target.name,
            confirmLabel = stringResource(UiR.string.rename),
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                viewModel.renameSaved(target, newName)
                renameTarget = null
            },
        )
    }

    if (showCreateList) {
        TextEntryDialog(
            title = stringResource(R.string.saved_lists_new),
            initial = "",
            confirmLabel = stringResource(UiR.string.create),
            onDismiss = { showCreateList = false },
            onConfirm = { name ->
                viewModel.createList(name)
                showCreateList = false
            },
        )
    }

    addToListTarget?.let { target ->
        AddToListDialog(
            lists = lists.keys.toList(),
            onDismiss = { addToListTarget = null },
            onPick = { listName ->
                viewModel.addToList(listName, target)
                addToListTarget = null
            },
            onCreateAndAdd = { listName ->
                viewModel.createList(listName)
                viewModel.addToList(listName, target)
                addToListTarget = null
            },
        )
    }
}

@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    onRename: () -> Unit,
    onAddToList: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SettingsRow(
        modifier = modifier,
        title = place.name,
        leadingContent = { IconStar() },
        trailingContent = {
            IconButton(onClick = { menuOpen = true }) { IconMoreVert() }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.rename)) },
                    onClick = { menuOpen = false; onRename() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_action_add_to_list)) },
                    onClick = { menuOpen = false; onAddToList() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(UiR.string.remove)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        },
    )
}

@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(title) },
            )
        },
    )
}

@Composable
private fun AddToListDialog(
    lists: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onCreateAndAdd(newName) }, enabled = newName.isNotBlank()) {
                Text(stringResource(UiR.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        },
        title = { Text(stringResource(R.string.saved_action_add_to_list)) },
        text = {
            Column {
                lists.forEach { name ->
                    SettingsRow(title = name, leadingContent = { IconList() }, onClick = { onPick(name) })
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.saved_lists_new)) },
                )
            }
        },
    )
}
