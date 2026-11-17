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
import androidx.compose.ui.Modifier
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
import com.vayunmathur.library.ui.appBarScrollBehavior
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
internal val DrawerWidth = 280.dp
internal val SavingSpinnerSize = 20.dp
private const val TIMER_TICK_MS = 1000L

/**
 * Error body for a document that failed to load.
 */
@Composable
internal fun MainActivity.EditorError(message: String, onOpenDocument: () -> Unit) {
    ErrorBody(message, onOpenDocument)
}

/** Error body content. */
@Composable
internal fun ErrorBody(message: String, onOpenDocument: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenDocument) {
            Text(stringResource(R.string.open_document))
        }
    }
}

/** Timer + back-handler effects for [DocumentScreen]. */
@Composable
internal fun DocumentEffects(
    s: DocumentScreenState,
    chrome: DocumentChrome,
    onBack: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
) {
    // Presentation timer
    LaunchedEffect(s.showTimer) {
        if (s.showTimer) {
            s.timerSeconds = 0
            while (s.showTimer) { kotlinx.coroutines.delay(TIMER_TICK_MS); s.timerSeconds++ }
        }
    }

    // Always intercept back inside a document so it returns to the home screen instead of exiting
    // the app. Online docs sync live (nothing to save); offline docs with edits prompt to save.
    BackHandler(enabled = true) {
        if (!chrome.isOnline && chrome.hasUnsavedChanges) onShowUnsavedDialog() else onBack()
    }
}

/** Navigation drawer + scaffold shell for [DocumentScreen]. */
@Composable
internal fun DocumentDrawerShell(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    drawerState: DrawerState,
    listState: LazyListState,
    onToggleSearch: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
    sel: ScreenSelection,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    focusedPara: Int,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome.isTextDoc &&
            (chrome.headings.isNotEmpty() || chrome.bookmarks.isNotEmpty()),
        drawerContent = {
            DrawerOutline(chrome, listState, drawerState, scope)
        }
    ) {
        DocumentScaffoldShell(
            s = s,
            document = document,
            viewModel = viewModel,
            activity = activity,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            drawerState = drawerState,
            listState = listState,
            onToggleSearch = onToggleSearch,
            onShowUnsavedDialog = onShowUnsavedDialog,
            sel = sel,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
            onBack = onBack,
        )
        DrawerScreenOverlays(
            s = s,
            document = document,
            viewModel = viewModel,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            listState = listState,
            focusedPara = focusedPara,
            onBack = onBack,
            onBecameOnline = onBecameOnline,
        )
    }
}

/** Outline drawer content. */
@Composable
internal fun DrawerOutline(
    chrome: DocumentChrome,
    listState: LazyListState,
    drawerState: DrawerState,
    scope: CoroutineScope,
) {
    if (!chrome.isTextDoc) return
    ModalDrawerSheet(Modifier.width(DrawerWidth)) {
        OfficeOutlinePane(
            chrome.bookmarks,
            chrome.headings,
            listState,
            drawerState,
            scope,
            Modifier.fillMaxWidth())
    }
}

/** Trailing overlays for the drawer shell. */
@Composable
internal fun DrawerScreenOverlays(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    listState: LazyListState,
    focusedPara: Int,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit,
) {
    DocumentOverlays(
            state = s,
            document = document,
            viewModel = viewModel,
            isPresentation = chrome.isPresentation,
            isOnline = chrome.isOnline,
            focusedPara = focusedPara,
            saveAsName = chrome.saveAsName,
            activeCell = s.activeCellState,
            activeSlide = s.activeSlideState,
            activeSlideEl = s.activeSlideElState,
            activeRunStart = s.activeRunStartState,
            activeRunEnd = s.activeRunEndState,
            selStart = s.selStartState,
            selEnd = s.selEndState,
            pendingReplace = s.pendingReplaceState,
            listState = listState,
            scope = scope,
            launchers = launchers,
            onBecameOnline = onBecameOnline,
            onBack = onBack,
        )
    }








