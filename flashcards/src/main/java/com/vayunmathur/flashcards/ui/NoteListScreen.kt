package com.vayunmathur.flashcards.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.flashcards.ui.NoteListActions
import com.vayunmathur.flashcards.ui.NoteListUiState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CommonSearchBar
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.FloatingActionButton
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconFolderOpen
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUpload
import com.vayunmathur.library.ui.IconVisibilityOff
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberSelectionState
import com.vayunmathur.library.util.sharedText

/**
 * The note list for one deck, with no dependency on the ViewModel or the back stack so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    state: NoteListUiState,
    actions: NoteListActions,
    onImport: () -> Unit = {},
    /** Morphs the deck's name out of its row on the deck list. Null when nothing morphs into it. */
    deckNameSharedKey: Any? = null,
) {
    var query by remember { mutableStateOf("") }
    var selectedTags by remember { mutableStateOf(emptySet<String>()) }
    val selection = rememberSelectionState<Long>()
    var showCustomStudy by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var tagDialog by remember { mutableStateOf<TagDialogKind?>(null) }
    var showReset by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }

    val byQuery = if (query.isBlank()) {
        state.notes
    } else {
        state.notes.filter {
            it.note.flds.contains(query, true) || it.note.tags.contains(query, true)
        }
    }
    val filtered = if (selectedTags.isEmpty()) {
        byQuery
    } else {
        byQuery.filter { row ->
            val tags = row.note.tags.split(" ").toSet()
            selectedTags.all { it in tags }
        }
    }
    val plainList = query.isNotBlank() || selectedTags.isNotEmpty() || selection.isActive

    AppScaffold(
        title = {
            if (selection.isActive) {
                Text(pluralStringResource(R.plurals.selected_count, selection.count, selection.count))
            } else {
                Text(
                    state.deckName,
                    modifier = if (deckNameSharedKey == null) Modifier else Modifier.sharedText(deckNameSharedKey),
                )
            }
        },
        onNavigateBack = { if (selection.isActive) selection.clear() else actions.back() },
        actions = {
            if (selection.isActive) {
                val ids = { selection.selected.toList() }
                IconButton(onClick = { showMove = true }) { IconFolderOpen() }
                IconButton(onClick = { actions.setSuspended(ids(), true); selection.clear() }) {
                    IconVisibilityOff()
                }
                IconButton(onClick = { actions.deleteNotes(ids()); selection.clear() }) { IconDelete() }
                IconButton(onClick = { overflow = true }) { IconMoreVert() }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.add_tag)) },
                        onClick = { overflow = false; tagDialog = TagDialogKind.ADD },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_tag)) },
                        onClick = { overflow = false; tagDialog = TagDialogKind.REMOVE },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.unsuspend)) },
                        onClick = {
                            overflow = false
                            actions.setSuspended(selection.selected.toList(), false)
                            selection.clear()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reset_scheduling)) },
                        onClick = { overflow = false; showReset = true },
                    )
                }
            } else {
                IconButton(onClick = onImport) { IconUpload() }
                IconButton(onClick = { actions.share() }) { IconShare() }
                IconButton(onClick = { overflow = true }) { IconMoreVert() }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.custom_study)) },
                        onClick = { overflow = false; showCustomStudy = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_csv)) },
                        onClick = { overflow = false; actions.exportCsv() },
                    )
                }
            }
        },
        floatingActionButton = {
            if (!selection.isActive) {
                FloatingActionButton(onClick = { actions.addNote() }) { IconAdd() }
            }
        },
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.dueCount > 0 && !selection.isActive) {
                Button(
                    onClick = { actions.study(selectedTags) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.study))
                }
            }
            if (state.tags.isNotEmpty()) {
                TagFilterRow(
                    tags = state.tags,
                    selected = selectedTags,
                    onToggle = { tag ->
                        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                    },
                )
            }
            if (state.notes.isNotEmpty()) {
                CommonSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.search_cards),
                    padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                !state.hasLoaded && state.notes.isEmpty() -> LoadingState(
                    modifier = Modifier.fillMaxSize(),
                )
                state.notes.isEmpty() -> EmptyState(
                    title = stringResource(R.string.no_cards),
                    message = stringResource(R.string.no_cards_hint),
                    icon = { IconAdd() },
                    modifier = Modifier.fillMaxSize(),
                )
                !plainList -> ReorderableNoteList(filtered, actions, selection)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(filtered, key = { it.note.id }) { row ->
                        NoteRowItem(
                            row = row,
                            selection = selection,
                            onOpen = {
                                if (selection.isActive) selection.toggle(row.note.id)
                                else actions.openNote(row.note.id)
                            },
                            onLongPress = { selection.toggle(row.note.id) },
                        )
                    }
                }
            }
        }
    }

    if (showCustomStudy) {
        CustomStudyDialog(
            onStart = { params ->
                showCustomStudy = false
                actions.customStudy(params, selectedTags)
            },
            onDismiss = { showCustomStudy = false },
        )
    }

    if (showMove) {
        MoveDeckDialog(
            decks = state.decks,
            onPick = { deckId ->
                showMove = false
                actions.moveNotes(selection.selected.toList(), deckId)
                selection.clear()
            },
            onDismiss = { showMove = false },
        )
    }

    tagDialog?.let { kind ->
        TagDialog(
            title = stringResource(if (kind == TagDialogKind.ADD) R.string.add_tag else R.string.remove_tag),
            onConfirm = { tag ->
                val ids = selection.selected.toList()
                if (kind == TagDialogKind.ADD) actions.addTag(ids, tag) else actions.removeTag(ids, tag)
                tagDialog = null
                selection.clear()
            },
            onDismiss = { tagDialog = null },
        )
    }

    if (showReset) {
        ConfirmDialog(
            title = stringResource(R.string.reset_scheduling),
            message = stringResource(R.string.reset_scheduling_message),
            confirmLabel = stringResource(R.string.reset_scheduling),
            dismissLabel = stringResource(UiR.string.cancel),
            destructive = true,
            onConfirm = {
                actions.resetScheduling(selection.selected.toList())
                showReset = false
                selection.clear()
            },
            onDismiss = { showReset = false },
        )
    }
}
