package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.needsSaveAs
import com.vayunmathur.office.platform.runParagraphIndexAt
import com.vayunmathur.office.platform.save

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

    // Desktop shortcuts at the editor root (preview phase, like the code editor's
    // root handler): Ctrl/Meta+S saves, Ctrl/Meta+B/I toggle the format the bottom
    // bar's formatter for the active target would toggle. Suppressed while any
    // dialog/menu overlay is open. Online documents sync live, so Save mirrors
    // the top-bar SaveAction, which only exists offline.
    val anyOverlayOpen = s.anyDialogOpen() || s.fileMenu || s.exportMenu || s.insertMenu || s.viewMenu
    fun toggleFormat(format: EditorFormat): Boolean {
        val formatter: com.vayunmathur.library.ui.EditorFormatter? = when {
            chrome.isTextDoc && sel.activeRunStart >= 0 -> TextRunFormatter(
                viewModel, sel.activeRunStart, sel.activeRunEnd, sel.selStart, sel.selEnd, enabled = true)
            chrome.isSpreadsheet && sel.activeCell != null -> sel.activeCell?.let { (sheet, row, col) ->
                CellFormatter(viewModel, sheet, row, col, enabled = true)
            }
            chrome.isPresentation && sel.activeSlideEl >= 0 ->
                SlideFormatter(viewModel, sel.activeSlide, sel.activeSlideEl, enabled = true)
            else -> null
        }
        formatter?.toggle(format)
        return formatter != null
    }
    // Keyboard focus traversal (Tab/Shift-Tab/Escape), at the same editor root
    // and with the same anyDialogOpen + open-menu suppression as the Ctrl/Meta
    // shortcuts below. Stops are every focusable in visual order (top bar incl.
    // the title, menu bar, body segments, bottom toolbar); Escape returns
    // focus to the first body stop unless the body already has it.
    val focusManager = LocalFocusManager.current
    val bodyFocusRequester = remember { FocusRequester() }
    var bodyHasFocus by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            handleOfficeDocumentKey(
                event = event,
                dialogOpen = anyOverlayOpen,
                onSave = {
                    if (chrome.isOnline) {
                        false
                    } else {
                        if (viewModel.needsSaveAs()) launchers.saveAs.launch(chrome.saveAsName)
                        else viewModel.save()
                        true
                    }
                },
                onToggleBold = { toggleFormat(EditorFormat.BOLD) },
                onToggleItalic = { toggleFormat(EditorFormat.ITALIC) },
            ) || handleOfficeFocusTraversalKey(
                event = event,
                dialogOpen = anyOverlayOpen,
                onNext = { focusManager.moveFocus(FocusDirection.Next) },
                onPrevious = { focusManager.moveFocus(FocusDirection.Previous) },
                onEscapeToBody = {
                    if (bodyHasFocus) false
                    else runCatching { bodyFocusRequester.requestFocus() }.isSuccess
                },
            )
        },
    ) {
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
        bodyFocusRequester = bodyFocusRequester,
        onBodyFocusChanged = { bodyHasFocus = it },
    )
    }
}

/** Run-selection + shell sections: see DocumentScreenChrome / DocumentScreenShell. */
