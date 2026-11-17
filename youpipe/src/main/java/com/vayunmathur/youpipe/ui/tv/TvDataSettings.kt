package com.vayunmathur.youpipe.ui.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvDialog
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.exportSubscriptions
import com.vayunmathur.youpipe.platform.importNewPipe
import com.vayunmathur.youpipe.platform.importYouTubeTakeout
import com.vayunmathur.youpipe.platform.restoreSubscriptions

/**
 * TV data settings: clear-history (with confirm dialog) + import/export rows.
 *
 * The phone launches SAF pickers via activity-result; on TV the same launchers fire — the
 * system file picker is itself D-pad traversable on Google TV. Import progress shows inline.
 */
@Composable
fun TvDataSettingsPage(
    ypvm: YouPipeViewModel,
) {
    val isLoading by ypvm.isImporting.collectAsState()
    val progress by ypvm.importProgress.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }

    val youtubeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) ypvm.importYouTubeTakeout(uri) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) ypvm.exportSubscriptions(uri) }
    val restoreLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) ypvm.restoreSubscriptions(uri) }
    val newPipeLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) ypvm.importNewPipe(uri) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TvScreenHeader(title = stringResource(R.string.settings_data))
        TvSettingsBody {
            TvDataSettingsRows(
                isLoading = isLoading,
                progress = progress,
                firstFocus = firstFocus,
                onClearClick = { confirmClear = true },
                onExport = { exportLauncher.launch("youpipe_subscriptions.json") },
                onRestore = { restoreLauncher.launch("application/json") },
                onImportNewPipe = { newPipeLauncher.launch("application/json") },
                onImportYouTube = { youtubeLauncher.launch("application/zip") },
            )
        }
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
private fun TvDataSettingsRows(
    isLoading: Boolean,
    progress: Float,
    firstFocus: FocusRequester,
    onClearClick: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onImportNewPipe: () -> Unit,
    onImportYouTube: () -> Unit,
) {
    Column(
        Modifier.padding(start = 48.dp, end = 48.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(progress = { progress })
        }
        Text(
            text = stringResource(R.string.title_history),
            style = MaterialTheme.typography.titleMedium,
        )
        TvListRow(
            headline = stringResource(R.string.clear_history),
            onClick = onClearClick,
            modifier = Modifier.focusRequester(firstFocus),
        )
        Text(
            text = stringResource(R.string.label_backup_restore),
            style = MaterialTheme.typography.titleMedium,
        )
        TvListRow(
            headline = stringResource(R.string.label_export_youpipe),
            onClick = onExport,
        )
        TvListRow(
            headline = stringResource(R.string.label_restore_youpipe),
            onClick = onRestore,
        )
        TvListRow(
            headline = stringResource(R.string.label_import_newpipe),
            onClick = onImportNewPipe,
        )
        TvListRow(
            headline = stringResource(R.string.label_import_youtube),
            onClick = onImportYouTube,
        )
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
        content = {},
    )
}
