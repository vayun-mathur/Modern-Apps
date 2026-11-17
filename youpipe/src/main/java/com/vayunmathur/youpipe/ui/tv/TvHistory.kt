package com.vayunmathur.youpipe.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvEmptyState
import com.vayunmathur.library.tv.TvGrid
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel

/**
 * TV history: a 4-column grid of video cards with D-pad multiselect and a clear-all
 * action in the header.
 *
 * The phone uses long-press to enter selection and a FAB to delete. A TV has no long-press
 * convention and no FAB within reach — CENTER on a card opens it, long-press (where the
 * remote has it) toggles selection, and delete/clear are focused buttons in the header.
 * BACK clears the selection first.
 */
@Composable
fun TvHistoryPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val history by ypvm.historyVideosByRecency.collectAsState()
    val historyById = remember(history) { history.associate { it.id to it.progress } }
    val videos = remember(history) { history.map { it.videoItem } }
    val rows = rememberTvVideoRows(videos, ypvm, historyById)

    var selectedIds by remember { mutableStateOf(emptySet<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val inSelectionMode = selectedIds.isNotEmpty()
    BackHandler(enabled = inSelectionMode) { selectedIds = emptySet() }

    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    Column(Modifier.fillMaxSize()) {
        TvHistoryHeader(
            inSelectionMode = inSelectionMode, selectedCount = selectedIds.size,
            hasRows = rows.isNotEmpty(),
            onDelete = {
                ypvm.deleteHistoryVideos(selectedIds.toList())
                selectedIds = emptySet()
            },
            onCancelSelection = { selectedIds = emptySet() },
            onClearClick = { confirmClear = true },
        )
        if (rows.isEmpty()) {
            TvEmptyState(title = stringResource(R.string.empty_recommendations))
            return@Column
        }
        TvHistoryGrid(
            modifier = Modifier.weight(1f), rows = rows, selectedIds = selectedIds,
            inSelectionMode = inSelectionMode, firstFocus = firstFocus,
            onOpen = { backStack.playVideo(it) },
            onToggle = { id, isSelected ->
                selectedIds = if (isSelected) {
                    selectedIds - id
                } else {
                    selectedIds + id
                }
            },
        )
    }
    if (confirmClear) {
        TvClearHistoryDialog(
            onDismiss = { confirmClear = false },
            onClear = {
                ypvm.clearHistory()
                confirmClear = false
            },
        )
    }
}

@Composable
private fun TvHistoryHeader(
    inSelectionMode: Boolean,
    selectedCount: Int,
    hasRows: Boolean,
    onDelete: () -> Unit,
    onCancelSelection: () -> Unit,
    onClearClick: () -> Unit,
) {
    TvScreenHeader(
        title = if (inSelectionMode) {
            stringResource(R.string.selected_count, selectedCount.toString())
        } else {
            stringResource(R.string.title_history)
        },
        actions = {
            if (inSelectionMode) {
                TvButton(
                    text = stringResource(UiR.string.delete),
                    onClick = onDelete,
                    icon = { IconDelete() },
                )
                TvButton(
                    text = stringResource(UiR.string.cancel),
                    onClick = onCancelSelection,
                )
            } else if (hasRows) {
                TvButton(
                    text = stringResource(R.string.clear_history),
                    onClick = onClearClick,
                    icon = { IconDelete() },
                )
            }
        },
    )
}

@Composable
private fun TvHistoryGrid(
    modifier: Modifier = Modifier,
    rows: List<TvVideoRow>,
    selectedIds: Set<Long>,
    inSelectionMode: Boolean,
    firstFocus: FocusRequester,
    onOpen: (Long) -> Unit,
    onToggle: (Long, Boolean) -> Unit,
) {
    TvGrid(columns = TV_GRID_COLUMNS, modifier = Modifier.fillMaxSize().then(modifier)) {
        items(rows.size, key = { rows[it].row.videoID }) { index ->
            val item = rows[index]
            val isSelected = item.row.videoID in selectedIds
            TvSelectableCard(
                item = item,
                selected = isSelected,
                inSelectionMode = inSelectionMode,
                onOpen = { onOpen(item.row.videoID) },
                onToggleSelect = { onToggle(item.row.videoID, isSelected) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun TvClearHistoryDialog(onDismiss: () -> Unit, onClear: () -> Unit) {
    TvDialog(
        title = stringResource(R.string.clear_history),
        onDismissRequest = onDismiss,
        body = stringResource(R.string.are_you_sure_you_want_to_clear_all_watch),
        buttons = {
            TvButton(
                text = stringResource(UiR.string.clear),
                onClick = onClear,
                primary = true,
            )
            TvButton(
                text = stringResource(UiR.string.cancel),
                onClick = onDismiss,
            )
        },
    )
}
