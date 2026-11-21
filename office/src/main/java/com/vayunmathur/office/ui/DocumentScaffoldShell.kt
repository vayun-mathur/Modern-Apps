package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.OfficeEditorWideLayout
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.needsSaveAs
import com.vayunmathur.office.platform.save
import kotlinx.coroutines.CoroutineScope
import com.vayunmathur.office.MainActivity
import com.vayunmathur.office.Navigation
import com.vayunmathur.office.R

/** App-bar scaffold with actions + bottom bar. */
@Composable
internal fun DocumentScaffoldShell(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleSearch: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
    sel: ScreenSelection,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    onBack: () -> Unit,
    bodyFocusRequester: FocusRequester? = null,
    onBodyFocusChanged: (Boolean) -> Unit = {},
) {
    AppScaffold(
        title = {
            // Title is the first keyboard stop after Back (visual order): a
            // focusable, ringed label so Tab can land on it and continue into
            // the body. Pointer/touch behaviour is unchanged (not clickable).
            var titleFocused by remember { mutableStateOf(false) }
            Text(
                document.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier
                    .onFocusChanged { titleFocused = it.isFocused }
                    .officeFocusRing(titleFocused)
                    .focusable())
        },
        navigationIcon = {
            IconButton(onClick = {
                if (!chrome.isOnline && chrome.hasUnsavedChanges) onShowUnsavedDialog() else onBack()
            }) {
                IconBack()
            }
        },
        actions = {
            DocumentScaffoldActions(
                chrome = chrome,
                viewModel = viewModel,
                canEdit = chrome.canEdit,
                saveAsName = chrome.saveAsName,
                launchers = launchers,
                onToggleSearch = onToggleSearch,
            )
        },
        bottomBar = {
            ScaffoldBottomBar(s, document, viewModel, chrome, launchers, sel)
        },
    ) { paddingValues ->
        DocumentScaffoldBody(
            s = s,
            document = document,
            viewModel = viewModel,
            activity = activity,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            drawerState = drawerState,
            listState = listState,
            contentPadding = paddingValues,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
            bodyFocusRequester = bodyFocusRequester,
            onBodyFocusChanged = onBodyFocusChanged,
        )
    }
}

/** Bottom bar for the document scaffold. */
@Composable
internal fun ScaffoldBottomBar(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    sel: ScreenSelection,
) {
    DocumentBottomBarHost(
        s, document, viewModel, chrome.canEdit, chrome.isTextDoc, chrome.isSpreadsheet, chrome.isPresentation,
        sel.activeRunStart, sel.activeRunEnd, sel.selStart, sel.selEnd, sel.activeCell,
        sel.activeSlide, sel.activeSlideEl, launchers,
        sel.activeTableBlock, sel.activeTableRow, sel.activeTableCol)
}

/** Top-app-bar actions for [DocumentScreen]. */
@Composable
internal fun DocumentScaffoldActions(
    chrome: DocumentChrome,
    viewModel: OfficeViewModel,
    canEdit: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
    onToggleSearch: () -> Unit,
) {
    if (canEdit) IconButton(onClick = { onToggleSearch() }) { IconSearch() }
    IconButton(onClick = { viewModel.undo() }, enabled = chrome.canUndo) { IconUndo() }
    IconButton(onClick = { viewModel.redo() }, enabled = chrome.canRedo) { IconRedo() }
    if (!chrome.isOnline) {
        SaveAction(viewModel, chrome, saveAsName, launchers)
    }
}

/** Save / save-as action (offline only). */
@Composable
internal fun SaveAction(
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    saveAsName: String,
    launchers: DocumentLaunchers,
) {
    IconButton(
        onClick = {
            if (viewModel.needsSaveAs()) {
                launchers.saveAs.launch(saveAsName)
            } else {
                viewModel.save()
            }
        },
        enabled = chrome.hasUnsavedChanges && !chrome.isSaving,
    ) {
        if (chrome.isSaving) CircularProgressIndicator(Modifier.size(SavingSpinnerSize)) else IconSave()
    }
}

