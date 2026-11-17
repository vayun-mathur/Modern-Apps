package com.vayunmathur.code.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.vayunmathur.code.platform.EditorViewModel
import com.vayunmathur.code.platform.openExternal

/**
 * Binds [EditorViewModel] to the stateless [EditorScreen].
 *
 * The two document pickers stay here: they need an activity result launcher, which is
 * exactly what a `@Preview` cannot provide.
 */
@Composable
fun EditorPage(
    viewModel: EditorViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onOpenGit: () -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenPreview: () -> Unit = {},
) {
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::openExternal) }
    val activity = LocalActivity.current

    EditorScreen(
        state = viewModel.uiState,
        actions = viewModel.actions,
        onOpenFolder = onOpenFolder,
        onOpenFile = { fileLauncher.launch(arrayOf("*/*")) },
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onOpenGit = onOpenGit,
        onOpenTerminal = onOpenTerminal,
        onOpenPreview = onOpenPreview,
        onExitApp = { activity?.finish() },
    )
}
