package com.vayunmathur.logviewer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.FloatingActionButtonMenu
import com.vayunmathur.library.ui.FloatingActionButtonMenuItem
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.MultiSelectDropdown
import com.vayunmathur.library.ui.SelectDropdown
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.ToggleFloatingActionButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.logviewer.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogLevel
import com.vayunmathur.logviewer.platform.ExtraAction
import com.vayunmathur.logviewer.platform.LogViewerActions
import com.vayunmathur.logviewer.platform.LogViewerUiState
import com.vayunmathur.logviewer.platform.LogcatReader
import com.vayunmathur.logviewer.ui.components.LogLineList
import com.vayunmathur.logviewer.ui.dialogs.StackTraceDialog
import com.vayunmathur.logviewer.ui.dialogs.TextInputDialog

/**
 * The one screen: a log, the actions that operate on it, and the filters that produced it.
 *
 * Stateless, so both entry points render it from their own [LogViewerUiState] and nothing about a
 * crash report or a logcat dump is baked into the layout.
 *
 * Copy, Share and Save live behind one share FAB. The bottom row is left for the actions specific
 * to what is on screen: Report when the sender says the report is worth filing, and the jumps to a
 * related log.
 */
@Composable
internal fun LogViewerScreen(state: LogViewerUiState, actions: LogViewerActions) {
    val scrollBehavior = appBarScrollBehavior()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    // True on large tablets, ChromeOS and desktop windows: the "unable to save"
    // stack trace renders as a side panel next to the log instead of a modal
    // dialog over it (see the scaffold content below).
    val expanded = isExpandedWidth()

    var showFilter by remember { mutableStateOf(false) }

    // This app has no MainNavigation to drain AppMessages, so the one screen it has does it.
    LaunchedEffect(snackbarHostState) {
        AppMessages.messages.collect { message ->
            snackbarHostState.showSnackbar(message.text)
        }
    }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(LogDocument.MIME_TYPE)
    ) { uri -> if (uri != null) actions.save(uri) }

    // A log is read from the end, so that is where it opens, and where a filter change lands. Only
    // for logcat: a stack trace is read from the top.
    LaunchedEffect(state.lines) {
        if (state.kind.scrollToBottom && state.lines.isNotEmpty()) {
            listState.scrollToItem(state.lines.lastIndex)
        }
    }

    AppScaffold(
        title = state.title,
        scrollBehavior = scrollBehavior,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        actions = {
            if (!state.loading && !state.unavailable) {
                val logcat = state.logcat
                if (logcat != null) {
                    IconButton(onClick = { showFilter = true }) { IconSearch() }
                    SelectDropdown(
                        label = logcat.level.letter.toString(),
                        options = LogLevel.entries,
                        selected = logcat.level,
                        itemLabel = { stringResource(it.labelRes) },
                        onSelect = actions::setLevel,
                    )
                    MultiSelectDropdown(
                        label = logcat.buffers.joinToString("") { it.take(1).uppercase() },
                        options = LogcatReader.ALL_BUFFERS,
                        selected = logcat.buffers,
                        itemLabel = { it },
                        onApply = actions::setBuffers,
                    )
                }
            }
        },
        floatingActionButton = {
            if (!state.loading && !state.unavailable) {
                ShareFab(
                    onCopy = actions::copy,
                    onShare = actions::share,
                    onSave = { save.launch(state.snapshotFileName) },
                )
            }
        },
        bottomBar = {
            if (!state.loading && !state.unavailable &&
                (state.showReportButton || state.extraActions.isNotEmpty())
            ) {
                BottomActions(state, actions)
            }
        },
    ) { scaffoldPadding ->
        // Room under the last line for the FAB, so the newest log line is never hidden behind it.
        val padding = PaddingValues(
            top = scaffoldPadding.calculateTopPadding(),
            bottom = scaffoldPadding.calculateBottomPadding() + FAB_CLEARANCE,
        )
        Box(Modifier.fillMaxSize()) {
            when {
                state.loading -> LoadingState(Modifier.padding(padding))

                state.unavailable -> {
                    val messageRes = state.unavailableMessageRes
                    // A failure with no message is already closing the window (see LogViewerPage).
                    // Rendering the fallback text here would flash the wrong reason on the way out.
                    if (messageRes != null) {
                        ErrorState(
                            title = stringResource(messageRes),
                            modifier = Modifier.padding(padding),
                        )
                    }
                }

                // Expanded windows (large tablets, desktop) put the log lines and the
                // "unable to save" stack trace side by side instead of stacking a
                // modal dialog over the log — there is room for both, and the trace
                // explains the log the user is already looking at.
                expanded && state.stackTrace != null -> Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    LogLineList(
                        lines = state.lines,
                        kind = state.kind,
                        fontSizeSp = state.fontSizeSp,
                        listState = listState,
                        contentPadding = PaddingValues(0.dp),
                        onZoom = actions::zoom,
                        // The 16dp gutter the app being replaced put around its whole layout. It stays on
                        // the list rather than the window so the top bar and the snackbar still go
                        // edge to edge.
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 16.dp),
                    )
                    StackTraceSidePanel(
                        stackTrace = state.stackTrace,
                        onCopy = actions::copyStackTrace,
                        onDismiss = actions::dismissStackTrace,
                        modifier = Modifier.fillMaxWidth(0.4f).fillMaxHeight(),
                    )
                }

                else -> LogLineList(
                    lines = state.lines,
                    kind = state.kind,
                    fontSizeSp = state.fontSizeSp,
                    listState = listState,
                    contentPadding = padding,
                    onZoom = actions::zoom,
                    // The 16dp gutter the app being replaced put around its whole layout. It stays on
                    // the list rather than the window so the top bar and the snackbar still go
                    // edge to edge.
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (state.refreshing) {
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = scaffoldPadding.calculateTopPadding())
                )
            }
        }
    }

    val logcat = state.logcat
    if (logcat != null && showFilter) {
        TextInputDialog(
            title = stringResource(R.string.set_filter),
            initialText = logcat.filterRegex,
            onConfirm = {
                showFilter = false
                actions.setFilter(it)
            },
            onDismiss = { showFilter = false },
            singleLine = true,
            hint = stringResource(R.string.set_filter_editor_hint),
        )
    }

    val stackTrace = state.stackTrace
    // On expanded widths the trace is already showing in the side panel next to
    // the log; the dialog is only for compact windows where there is no room.
    if (stackTrace != null && !expanded) {
        StackTraceDialog(
            stackTrace = stackTrace,
            onCopy = actions::copyStackTrace,
            onDismiss = actions::dismissStackTrace,
        )
    }
}

/**
 * The "unable to save" stack trace as a permanent side panel on expanded
 * windows, instead of the modal [StackTraceDialog] compact windows use. Same
 * content (monospaced trace, copy + dismiss), different chrome: a card pinned
 * next to the log so both stay visible together.
 */
@Composable
private fun StackTraceSidePanel(
    stackTrace: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.padding(12.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                stringResource(R.string.unable_to_save_file),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stackTrace,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
                Button(onClick = { onCopy(); onDismiss() }) { Text(stringResource(UiR.string.copy)) }
            }
        }
    }
}

@Composable
private fun BottomActions(state: LogViewerUiState, actions: LogViewerActions) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (state.showReportButton) {
            Button(onClick = actions::report) { Text(stringResource(R.string.action_report)) }
        }
        for (extra in state.extraActions) {
            Button(onClick = { actions.perform(extra) }) { Text(stringResource(extra.labelRes)) }
        }
    }
}

private val FAB_CLEARANCE = 88.dp

/** Copy, Share and Save, opening upward from one share FAB. */
@Composable
private fun ShareFab(onCopy: () -> Unit, onShare: () -> Unit, onSave: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    FloatingActionButtonMenu(
        expanded = open,
        button = {
            ToggleFloatingActionButton(checked = open, onCheckedChange = { open = it }) {
                if (open) IconClose() else IconShare()
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { open = false; onCopy() },
            text = { Text(stringResource(UiR.string.copy)) },
            icon = { IconCopy() },
        )
        FloatingActionButtonMenuItem(
            onClick = { open = false; onShare() },
            text = { Text(stringResource(UiR.string.share)) },
            icon = { IconShare() },
        )
        FloatingActionButtonMenuItem(
            onClick = { open = false; onSave() },
            text = { Text(stringResource(UiR.string.save)) },
            icon = { IconSave() },
        )
    }
}

private val LogLevel.labelRes: Int
    get() = when (this) {
        LogLevel.Verbose -> R.string.log_level_verbose
        LogLevel.Debug -> R.string.log_level_debug
        LogLevel.Info -> R.string.log_level_info
        LogLevel.Warn -> R.string.log_level_warn
        LogLevel.Error -> R.string.log_level_error
        LogLevel.Assert -> R.string.log_level_assert
    }

private val ExtraAction.labelRes: Int
    get() = when (this) {
        ExtraAction.MoreInfo -> R.string.action_more_info
        ExtraAction.ShowAppLog -> R.string.action_show_log
    }
