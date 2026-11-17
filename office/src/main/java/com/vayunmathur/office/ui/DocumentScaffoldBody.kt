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

/** Scaffold body: menu bar, find bars, and the editor pane. */
@Composable
internal fun DocumentScaffoldBody(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScaffoldMenuBars(
            s = s, document = document, viewModel = viewModel,
            activity = activity, chrome = chrome, launchers = launchers,
            scope = scope, drawerState = drawerState, listState = listState)
        // Timer/search/font/word bars: see DocumentFindBars (file-length split).
        DocumentEditorPane(
            s = s,
            document = document,
            viewModel = viewModel,
            chrome = chrome,
            listState = listState,
            scope = scope,
            drawerState = drawerState,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
            modifier = modifier,
        )
    }
}

/** Menu + find bars for the scaffold body. */
@Composable
internal fun ScaffoldMenuBars(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    DocumentMenuBar(
        s = s,
        document = document,
        viewModel = viewModel,
        activity = activity,
        isTextDoc = chrome.isTextDoc,
        isSpreadsheet = chrome.isSpreadsheet,
        isPresentation = chrome.isPresentation,
        isOnline = chrome.isOnline,
        onlineEnabled = chrome.onlineEnabled,
        hasUnsavedChanges = chrome.hasUnsavedChanges,
        saveAsName = chrome.saveAsName,
        wordCount = chrome.wordCount,
        charCount = chrome.charCount,
        readingTime = chrome.readingTime,
        nightMode = chrome.nightMode,
        documentDarkMode = chrome.documentDarkMode,
        launchers = launchers,
        scope = scope,
        drawerState = drawerState,
    )
    DocumentFindBars(
        s = s,
        document = document,
        viewModel = viewModel,
        isTextDoc = chrome.isTextDoc,
        isPresentation = chrome.isPresentation,
        wordCount = chrome.wordCount,
        charCount = chrome.charCount,
        readingTime = chrome.readingTime,
        scope = scope,
        listState = listState,
    )
}

/** Editor pane with the wide-layout outline branch. */
@Composable
internal fun DocumentEditorPane(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val showOutline = isExpandedWidth() && chrome.isTextDoc &&
            (chrome.headings.isNotEmpty() || chrome.bookmarks.isNotEmpty())
        if (showOutline) {
            OfficeEditorWideLayout(
                outline = { OfficeOutlinePane(
                    chrome.bookmarks,
                    chrome.headings,
                    listState,
                    drawerState,
                    scope,
                    Modifier.fillMaxSize()) },
                document = {
                    EditorDocumentPane(
                        s = s, document = document, viewModel = viewModel, chrome = chrome,
                        listState = listState, onRunSelectionChange = onRunSelectionChange,
                        onCellFocus = onCellFocus, onCellSelected = onCellSelected,
                        onSlideChange = onSlideChange, onSlideElementSelected = onSlideElementSelected,
                        modifier = Modifier.fillMaxSize())
                },
            )
        } else {
            EditorDocumentPane(
                s = s, document = document, viewModel = viewModel, chrome = chrome,
                listState = listState, onRunSelectionChange = onRunSelectionChange,
                onCellFocus = onCellFocus, onCellSelected = onCellSelected,
                onSlideChange = onSlideChange, onSlideElementSelected = onSlideElementSelected,
                modifier = Modifier.fillMaxSize())
        }
    }
}

/** Single shared [OfficeDocumentPane] call (compact + wide layouts). */
@Composable
internal fun EditorDocumentPane(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSlideEl by s.activeSlideElState
    OfficeDocumentPane(
        document = document,
        viewModel = viewModel,
        documentDarkMode = chrome.documentDarkMode,
        nightMode = chrome.nightMode,
        searchQuery = s.searchQuery,
        fontSizeMultiplier = s.fontSizeMultiplier,
        listState = listState,
        isEditMode = chrome.isEditMode,
        activeSlideEl = activeSlideEl,
        onRunSelectionChange = onRunSelectionChange,
        onCellFocus = onCellFocus,
        onCellSelected = onCellSelected,
        onSlideChange = onSlideChange,
        onSlideElementSelected = onSlideElementSelected,
        onChartClick = { bi -> s.editingChartBlock = bi; s.showChartEditor = true },
        onCropImage = { bi -> s.cropImageBlock = bi },
        onCropSlide = { si, e -> s.cropSlideTarget = si to e },
        onCropSheet = { si, e -> s.cropSheetTarget = si to e },
        modifier = modifier,
    )
}

