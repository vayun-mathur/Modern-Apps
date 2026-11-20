package com.vayunmathur.uniflix.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.ui.friendlyError
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.uniflix.platform.AddonsViewModel
import com.vayunmathur.uniflix.ui.dialogs.AddAddonDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val JS_MIME_TYPES = arrayOf("text/javascript", "application/javascript", "text/plain")

/**
 * Addon manager: installed URL addons with enable toggle, default star,
 * priority ordering, error badges, and add-by-URL. State lives here; the
 * stateless [AddonsContent] body renders previews without a ViewModel.
 */
@Composable
fun AddonsScreen(onBack: () -> Unit, onOpenDetail: (addonDbId: Long) -> Unit) {
    val vm: AddonsViewModel = viewModel()
    val rows by vm.addons.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: error("Could not read file")
                }
                vm.addJsPlugin(source)
            }.onFailure {
                AppMessages.show(context.friendlyError(it))
            }
        }
    }
    val importJs: () -> Unit = { importLauncher.launch(JS_MIME_TYPES) }

    AddonsContent(
        rows = rows,
        adding = vm.adding,
        refreshing = vm.refreshing,
        onBack = onBack,
        onAddClick = { showAddDialog = true },
        onImportJsClick = importJs,
        onToggle = { row -> vm.setEnabled(row, !row.entity.enabled) },
        onSetDefault = { row -> vm.setDefault(row) },
        onClearDefault = { vm.clearDefault() },
        onMoveUp = { row -> vm.moveUp(rows, row) },
        onMoveDown = { row -> vm.moveDown(rows, row) },
        onRemove = { row -> vm.remove(row) },
        onRefresh = { row -> vm.refreshManifest(row) },
        onRefreshAll = { vm.refreshAll() },
        onOpenDetail = onOpenDetail,
    )

    if (showAddDialog) {
        AddAddonDialog(
            adding = vm.adding,
            onDismiss = { showAddDialog = false },
            onConfirm = { url ->
                vm.addByUrl(url)
                showAddDialog = false
            },
            onImportJs = {
                showAddDialog = false
                importJs()
            },
        )
    }
}
