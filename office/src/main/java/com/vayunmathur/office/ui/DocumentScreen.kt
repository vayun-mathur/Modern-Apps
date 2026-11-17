package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.runParagraphIndexAt

/**
 * Error body for a document that failed to load: see DocumentScreenShell.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit = {}) {
    val s = rememberDocumentScreenState()
    val sel = rememberScreenSelection(s)

    val onRunSelectionChange = rememberRunSelectionHandler(sel, viewModel)

    val chrome = rememberDocumentChrome(viewModel, document)
    val focusedPara = if (sel.activeRunStart >= 0 && chrome.isTextDoc) viewModel.runParagraphIndexAt(
        sel.activeRunStart,
        sel.activeRunEnd,
        sel.selStart) else -1

    DocumentEffects(s, chrome, onBack, { sel.showUnsavedDialog = true })

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val launchers = rememberDocumentLaunchers(
        viewModel = viewModel,
        holder = s,
        onPickImageBytes = rememberImagePickerHandler(
            document, viewModel, focusedPara, sel.activeSlide, sel.activeCell))

    DocumentDrawerShell(
        s = s,
        document = document,
        viewModel = viewModel,
        activity = activity,
        chrome = chrome,
        launchers = launchers,
        scope = scope,
        drawerState = drawerState,
        listState = listState,
        onToggleSearch = { sel.showSearch = !sel.showSearch },
        onShowUnsavedDialog = { sel.showUnsavedDialog = true },
        sel = ScreenSelection(
            sel.activeRunStart, sel.activeRunEnd, sel.activeTableBlock, sel.activeTableRow, sel.activeTableCol,
            sel.selStart, sel.selEnd, sel.activeCell, sel.activeSlide, sel.activeSlideEl),
        onRunSelectionChange = onRunSelectionChange,
        onCellFocus = { bi, r, c -> sel.activeTableBlock = bi; sel.activeTableRow = r; sel.activeTableCol = c },
        onCellSelected = { si, r, c -> sel.activeCell = Triple(si, r, c) },
        onSlideChange = { sel.activeSlide = it },
        onSlideElementSelected = { si, e -> sel.activeSlide = si; sel.activeSlideEl = e },
        focusedPara = focusedPara,
        onBack = onBack,
        onBecameOnline = onBecameOnline,
    )
}

/** Run-selection + shell sections: see DocumentScreenChrome / DocumentScreenShell. */
