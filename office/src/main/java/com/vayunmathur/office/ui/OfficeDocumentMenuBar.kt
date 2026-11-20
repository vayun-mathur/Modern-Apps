package com.vayunmathur.office.ui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.extractHeadings
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.save
import com.vayunmathur.office.platform.needsSaveAs
import com.vayunmathur.office.platform.exportAsPlainText
import com.vayunmathur.office.platform.fieldDisplayValue
import com.vayunmathur.office.platform.insertFieldInRun
import com.vayunmathur.office.platform.insertHorizontalLine
import com.vayunmathur.office.platform.insertPageBreak
import com.vayunmathur.office.platform.insertTableOfContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.vayunmathur.office.R
import com.vayunmathur.office.platform.runParagraphIndexAt
/**
 * Office-style menu bar (File / Insert / View) for [DocumentScreen].
 *
 * Moved verbatim (split for file length); the screen's locals became [DocumentScreenState]
 * reads/writes, behavior identical.
 */
@Composable
fun DocumentMenuBar(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    isTextDoc: Boolean,
    isSpreadsheet: Boolean = false,
    isPresentation: Boolean,
    isOnline: Boolean,
    onlineEnabled: Boolean,
    hasUnsavedChanges: Boolean,
    saveAsName: String,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
    nightMode: Boolean,
    documentDarkMode: Boolean,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    drawerState: DrawerState,
) {
    val context = LocalContext.current
    val headings = if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList()
    val bookmarks = if (document is OdfDocument.TextDocument) document.bookmarks else emptyList()
    val menuFocusedPara = if (s.activeRunStartState.intValue >= 0 && isTextDoc) {
        viewModel.runParagraphIndexAt(
            s.activeRunStartState.intValue,
            s.activeRunEndState.intValue,
            s.selStartState.intValue)
    } else {
        -1
    }
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            FileMenu(
                s, document, viewModel, activity, isOnline, onlineEnabled,
                hasUnsavedChanges, saveAsName, launchers, context)
            ExportMenu(
                s, document, viewModel, isTextDoc, isSpreadsheet, isPresentation, launchers)
            // Edit menu removed: Search moved to a top
            // Insert
            InsertMenu(s, viewModel, isTextDoc, menuFocusedPara, launchers)
            // Format menu removed: font size, clear fo
            // menu.
            ViewMenu(
                s, viewModel, isTextDoc, isPresentation, headings, bookmarks,
                wordCount, charCount, readingTime, nightMode, documentDarkMode,
                scope, drawerState)
        }
    }
}

/** File menu (save/share/print/export/settings). */
@Composable
private fun FileMenu(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    isOnline: Boolean,
    onlineEnabled: Boolean,
    hasUnsavedChanges: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
    context: android.content.Context,
) {
    Box {
        TextButton(onClick = { s.fileMenu = true }) { Text(stringResource(R.string.file)) }
        DropdownMenu(expanded = s.fileMenu, onDismissRequest = { s.fileMenu = false }) {
            if (!isOnline) {
                SaveMenuItems(s, viewModel, hasUnsavedChanges, saveAsName, launchers)
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.synced_to_cloud)) },
                    enabled = false,
                    leadingIcon = { IconSave() },
                    onClick = {})
            }
            ShareOnlineItem(s, onlineEnabled)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.print_doc)) },
                onClick = { s.fileMenu = false; printDocument(activity, document) })
            ShareFileItem(s, viewModel, context)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export)) },
                leadingIcon = { IconDownload() },
                onClick = { s.fileMenu = false; s.exportMenu = true })
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(UiR.string.settings)) },
                leadingIcon = { IconSettings() },
                onClick = { s.fileMenu = false; s.showSettings = true })
        }
    }
}

/** Save + save-as items (offline only). */
@Composable
private fun SaveMenuItems(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    hasUnsavedChanges: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
) {
    DropdownMenuItem(
        text = { Text(stringResource(UiR.string.save)) },
        enabled = hasUnsavedChanges,
        leadingIcon = { IconSave() },
        onClick = {
            s.fileMenu = false
            if (viewModel.needsSaveAs()) {
                launchers.saveAs.launch(saveAsName)
            } else {
                viewModel.save()
            }
        },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.save_as_1)) },
        onClick = { s.fileMenu = false; launchers.saveAs.launch(saveAsName) })
}

/** Share-online item (opens share or enable-online dialog). */
@Composable
private fun ShareOnlineItem(s: DocumentScreenState, onlineEnabled: Boolean) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.share_online_1)) },
        leadingIcon = { IconShare() },
        onClick = {
            s.fileMenu = false
            if (onlineEnabled) {
                s.showShareDialog = true
            } else {
                s.showEnableOnlineDialog = true
            }
        },
    )
}

/** System share-sheet item for the source file. */
@Composable
private fun ShareFileItem(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    context: android.content.Context,
) {
    viewModel.originalUri?.let { uri ->
        DropdownMenuItem(
            text = { Text(stringResource(UiR.string.share)) },
            leadingIcon = { IconShare() },
            onClick = {
                s.fileMenu = false
                val send = Intent(Intent.ACTION_SEND).apply {
                    // The provider's real type for this document; */* only when
                    // the provider declines to name one.
                    type = context.contentResolver.getType(uri) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, null))
            },
        )
    }
}

/** View menu (outline/zoom/theme/word-bar/comments/timer). */
@Composable
private fun ViewMenu(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    isTextDoc: Boolean,
    isPresentation: Boolean,
    headings: List<com.vayunmathur.office.ui.HeadingItem>,
    bookmarks: List<com.vayunmathur.library.ui.odf.OdfBookmark>,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
    nightMode: Boolean,
    documentDarkMode: Boolean,
    scope: CoroutineScope,
    drawerState: DrawerState,
) {
    Box {
        TextButton(onClick = { s.viewMenu = true }) { Text(stringResource(R.string.view)) }
        DropdownMenu(expanded = s.viewMenu, onDismissRequest = { s.viewMenu = false }) {
            if (isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty())) DropdownMenuItem(
                text = { Text(stringResource(R.string.outline)) },
                onClick = { s.viewMenu = false; scope.launch { drawerState.open() } })
            DropdownMenuItem(
                text = { Text(stringResource(R.string.zoom_text)) },
                onClick = { s.viewMenu = false; s.showFontControl = !s.showFontControl })
            ThemeMenuItems(s, viewModel, nightMode, documentDarkMode)
            TextDocViewItems(s, isTextDoc, wordCount, charCount, readingTime)
            if (isPresentation) DropdownMenuItem(
                text = { Text(stringResource(R.string.presentation_timer)) },
                onClick = { s.viewMenu = false; s.showTimer = !s.showTimer })
        }
    }
}

/** Night + document dark-mode toggles. */
@Composable
private fun ThemeMenuItems(
    s: DocumentScreenState,
    viewModel: OfficeViewModel,
    nightMode: Boolean,
    documentDarkMode: Boolean,
) {
    DropdownMenuItem(
        text = {
            Text(
                if (nightMode) {
                    stringResource(R.string.night_reading_mode)
                } else {
                    stringResource(R.string.night_reading_mode_1)
                },
            )
        },
        onClick = { s.viewMenu = false; viewModel.toggleNightMode() },
    )
    DropdownMenuItem(
        text = {
            Text(
                if (documentDarkMode) {
                    stringResource(R.string.document_dark_mode)
                } else {
                    stringResource(R.string.document_dark_mode_1)
                },
            )
        },
        onClick = { s.viewMenu = false; viewModel.toggleDocumentDarkMode() },
    )
}

/** Text-doc view items (word bar/comments/changes/page setup/stats). */
@Composable
private fun TextDocViewItems(
    s: DocumentScreenState,
    isTextDoc: Boolean,
    wordCount: Int,
    charCount: Int,
    readingTime: Int,
) {
    if (!isTextDoc) return
    DropdownMenuItem(
        text = {
            Text(
                if (s.showWordBar) {
                    stringResource(R.string.word_count_bar)
                } else {
                    stringResource(R.string.word_count_bar_1)
                },
            )
        },
        onClick = { s.viewMenu = false; s.showWordBar = !s.showWordBar },
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.comments)) },
        onClick = { s.viewMenu = false; s.showComments = true })
    DropdownMenuItem(
        text = { Text(stringResource(R.string.track_changes)) },
        onClick = { s.viewMenu = false; s.showChanges = true })
    DropdownMenuItem(
        text = { Text(stringResource(R.string.page_setup)) },
        onClick = { s.viewMenu = false; s.showPageSetup = true })
    if (wordCount > 0) DropdownMenuItem(
        text = { Text(stringResource(R.string.words_chars_min, wordCount, charCount, readingTime)) },
        enabled = false,
        onClick = { })
}
