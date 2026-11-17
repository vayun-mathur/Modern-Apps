package com.vayunmathur.office

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.clearDocument
import com.vayunmathur.office.platform.initSync
import com.vayunmathur.office.platform.loadDocument
import com.vayunmathur.office.platform.openOnlineDocument
import com.vayunmathur.office.ui.DocumentScreen
import com.vayunmathur.office.ui.EditorError
import com.vayunmathur.office.ui.OfficeAppTheme
import com.vayunmathur.office.ui.OfficeTabs

/** Root navigation graph: home tabs plus the document editor routes. */
@Composable
fun Navigation(viewModel: OfficeViewModel, activity: MainActivity, intentUri: Uri?) {
    val startedWithIntent = intentUri != null
    var documentUri by rememberSaveable { mutableStateOf(intentUri) }
    val state by viewModel.state.collectAsState()
    val backStack = rememberNavBackStack<Route>(
        if (intentUri != null) Route.OfflineEditor(intentUri.toString()) else Route.OfflineHome
    )

    LaunchedEffect(Unit) { viewModel.initSync() }

    if (documentUri != null && state is OfficeViewModel.ViewState.Empty) {
        viewModel.loadDocument(documentUri!!, activity.resolveDisplayName(activity, documentUri!!))
    }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            activity.persistUriGrant(it)
            documentUri = it
            viewModel.loadDocument(it, activity.resolveDisplayName(activity, it))
            backStack.add(Route.OfflineEditor(it.toString()))
        }
    }

    fun leaveEditor() {
        if (startedWithIntent) activity.finish()
        else {
            documentUri = null
            viewModel.clearDocument()
            if (backStack.backStack.size > 1) backStack.pop()
        }
    }

    OfficeAppTheme {
        val editorContent: @Composable () -> Unit = {
            when (val s = state) {
                is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                    document = s.document, viewModel = viewModel, activity = activity,
                    onBack = { leaveEditor() },
                    // When an offline doc is shared it becomes a cloud doc — switch its route.
                    onBecameOnline = { id -> backStack.setLast(Route.OnlineEditor(id)) }
                )
                is OfficeViewModel.ViewState.Error -> activity.EditorError(s.message, { leaveEditor() })
                else -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        OfficeTabs(
            backStack = backStack,
            viewModel = viewModel,
            onOpenDocument = { filePickerLauncher.launch(activity.odfMimeTypes) },
            onNavigateEditor = { backStack.add(Route.OfflineEditor()) },
            onOpenOnlineDoc = { meta ->
                viewModel.openOnlineDocument(meta)
                backStack.add(Route.OnlineEditor(meta.docId))
            },
            editorContent = editorContent,
        )
    }
}
