package com.vayunmathur.youpipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ConfirmDialog
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.exportSubscriptions
import com.vayunmathur.youpipe.platform.importNewPipe
import com.vayunmathur.youpipe.platform.importYouTubeTakeout
import com.vayunmathur.youpipe.platform.restoreSubscriptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val isLoading by ypvm.isImporting.collectAsState()
    val progress by ypvm.importProgress.collectAsState()
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importYouTubeTakeout(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ypvm.exportSubscriptions(uri)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.restoreSubscriptions(uri)
    }
    val newPipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importNewPipe(uri)
    }

    AppScaffold(
        title = stringResource(R.string.settings_data),
        backStack = backStack,
    ) { padding ->
        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator({ progress }, modifier = Modifier.align(Alignment.Center))
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsSection(title = stringResource(R.string.title_history)) {
                    SettingsRow(
                        title = stringResource(R.string.clear_history),
                        onClick = { showClearHistoryDialog = true },
                    )
                }
                SettingsSection(title = stringResource(R.string.label_backup_restore)) {
                    SettingsRow(
                        title = stringResource(R.string.label_export_youpipe),
                        onClick = { exportLauncher.launch("youpipe_subscriptions.json") },
                    )
                    SettingsRow(
                        title = stringResource(R.string.label_restore_youpipe),
                        onClick = { restoreLauncher.launch("application/json") },
                    )
                    SettingsRow(
                        title = stringResource(R.string.label_import_newpipe),
                        onClick = { newPipeLauncher.launch("application/json") },
                    )
                    SettingsRow(
                        title = stringResource(R.string.label_import_youtube),
                        onClick = { youtubeLauncher.launch("application/zip") },
                    )
                }
            }
        }
    }

    if (showClearHistoryDialog) {
        ConfirmDialog(
            title = stringResource(R.string.clear_history),
            message = stringResource(R.string.are_you_sure_you_want_to_clear_all_watch),
            confirmLabel = stringResource(UiR.string.clear),
            dismissLabel = stringResource(UiR.string.cancel),
            onConfirm = { ypvm.clearHistory() },
            onDismiss = { showClearHistoryDialog = false },
            destructive = true,
        )
    }
}
