package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.office.ui.AddBookmarkDialog
import com.vayunmathur.office.ui.ChartEditorDialog
import com.vayunmathur.office.ui.ColorPickerDialog
import com.vayunmathur.office.ui.CommentDialog
import com.vayunmathur.office.ui.FontSizePickerDialog
import com.vayunmathur.office.ui.FootnoteDialog
import com.vayunmathur.office.ui.HeaderFooterDialog
import com.vayunmathur.office.ui.ImageCropDialog
import com.vayunmathur.office.ui.InsertHyperlinkDialog
import com.vayunmathur.office.ui.InsertTableDialog
import com.vayunmathur.office.ui.SettingsDialog
import com.vayunmathur.office.ui.SpecialCharsDialog
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.save
import com.vayunmathur.office.platform.needsSaveAs
import com.vayunmathur.office.platform.acceptAllChanges
import com.vayunmathur.office.platform.acceptChange
import com.vayunmathur.office.platform.addBookmark
import com.vayunmathur.office.platform.applyRunSpanStyle
import com.vayunmathur.office.platform.cellCommentText
import com.vayunmathur.office.platform.currentDocRole
import com.vayunmathur.office.platform.currentOnlineDocId
import com.vayunmathur.office.platform.documentMembers
import com.vayunmathur.office.platform.enableOnlineSharing
import com.vayunmathur.office.platform.initSync
import com.vayunmathur.office.platform.insertChart
import com.vayunmathur.office.platform.insertChartIntoSheet
import com.vayunmathur.office.platform.insertChartIntoSlide
import com.vayunmathur.office.platform.insertComment
import com.vayunmathur.office.platform.insertFootnote
import com.vayunmathur.office.platform.insertHyperlink
import com.vayunmathur.office.platform.insertTable
import com.vayunmathur.office.platform.insertTextInRun
import com.vayunmathur.office.platform.replaceSheetImage
import com.vayunmathur.office.platform.replaceSlideImage
import com.vayunmathur.office.platform.replaceTextImage
import com.vayunmathur.office.platform.rotateSheetImage
import com.vayunmathur.office.platform.rotateSlideImage
import com.vayunmathur.office.platform.rotateTextImage
import com.vayunmathur.office.platform.securityCodeWith
import com.vayunmathur.office.platform.setCellBgColor
import com.vayunmathur.office.platform.setCellBorder
import com.vayunmathur.office.platform.setCellColor
import com.vayunmathur.office.platform.setCellComment
import com.vayunmathur.office.platform.setColumnWidth
import com.vayunmathur.office.platform.setFooterText
import com.vayunmathur.office.platform.setHeaderText
import com.vayunmathur.office.platform.setImageCrop
import com.vayunmathur.office.platform.setMemberRole
import com.vayunmathur.office.platform.setPageSetup
import com.vayunmathur.office.platform.setRowHeight
import com.vayunmathur.office.platform.setSheetImageCrop
import com.vayunmathur.office.platform.setSlideBackgroundColor
import com.vayunmathur.office.platform.setSlideElementColor
import com.vayunmathur.office.platform.setSlideElementFill
import com.vayunmathur.office.platform.setSlideElementStroke
import com.vayunmathur.office.platform.setSlideImageCrop
import com.vayunmathur.office.platform.setSlideNotes
import com.vayunmathur.office.platform.setSlideTransition
import com.vayunmathur.office.platform.shareCurrentDocument
import com.vayunmathur.office.platform.shareLinkFor
import com.vayunmathur.office.platform.slideNotesText
import com.vayunmathur.office.platform.updateChart
import com.vayunmathur.office.platform.updateMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.vayunmathur.office.platform.OfficeMember
import com.vayunmathur.office.platform.OfficeRoles
import com.vayunmathur.office.R
import com.vayunmathur.office.platform.rejectAllChanges
import com.vayunmathur.office.platform.rejectChange
import com.vayunmathur.office.platform.renameDocument
import com.vayunmathur.office.platform.resolveComment
import com.vayunmathur.office.platform.transferOwnership

/** Slide floating-image crop dialog. */
@Composable
internal fun SlideCropOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
) {
    val (s, e) = state.cropSlideTarget ?: return
    val el = (document as? OdfDocument.Presentation)?.slides?.getOrNull(s)?.elements?.getOrNull(e)
    val img = (el as? OdfSlideElement.Frame)?.frame?.image
    if (img == null) {
        state.cropSlideTarget = null
        return
    }
    ImageCropDialog(
        image = img,
        onApply = { l, t, r, b -> viewModel.setSlideImageCrop(s, e, l, t, r, b) },
        onDismiss = { state.cropSlideTarget = null },
        onRotate = { viewModel.rotateSlideImage(s, e, ROTATE_QUARTER_TURNS) },
        onReplace = {
            pendingReplace.value = { n, b -> viewModel.replaceSlideImage(s, e, n, b) }
            launchers.replaceImage.launch("image/*")
        },
    )
}

/** Sheet floating-image crop dialog. */
@Composable
internal fun SheetCropOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
) {
    val (s, e) = state.cropSheetTarget ?: return
    val el = (document as? OdfDocument.Spreadsheet)?.sheets?.getOrNull(s)?.floating?.getOrNull(e)
    val img = (el as? OdfSlideElement.Frame)?.frame?.image
    if (img == null) {
        state.cropSheetTarget = null
        return
    }
    ImageCropDialog(
        image = img,
        onApply = { l, t, r, b -> viewModel.setSheetImageCrop(s, e, l, t, r, b) },
        onDismiss = { state.cropSheetTarget = null },
        onRotate = { viewModel.rotateSheetImage(s, e, ROTATE_QUARTER_TURNS) },
        onReplace = {
            pendingReplace.value = { n, b -> viewModel.replaceSheetImage(s, e, n, b) }
            launchers.replaceImage.launch("image/*")
        },
    )
}

/** Share / unsaved-changes / export-warning overlays. */
@Composable
internal fun ShareOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isOnline: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
    onBecameOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (state.showEnableOnlineDialog) {
        EnableOnlineDialog(
            onEnable =
                { viewModel.enableOnlineSharing(); state.showEnableOnlineDialog = false; state.showShareDialog = true },
            onDismiss = { state.showEnableOnlineDialog = false }
        )
    }
    if (state.showShareDialog) {
        ShareDialogBody(state, document, viewModel, isOnline, onBecameOnline)
    }
    if (state.showUnsavedDialog) AlertDialog(
        onDismissRequest = { state.showUnsavedDialog = false },
        title = { Text(stringResource(R.string.unsaved_changes)) },
        text = { Text(stringResource(R.string.unsaved_changes_message)) },
        confirmButton = { TextButton(onClick = { state.showUnsavedDialog = false; onBack() }) { Text(
            stringResource(R.string.discard),
            color = MaterialTheme.colorScheme.error) } },
        dismissButton = { Row {
            TextButton(onClick = { state.showUnsavedDialog = false }) { Text(stringResource(UiR.string.cancel)) }
            TextButton(onClick = {
                state.showUnsavedDialog = false
                if (viewModel.needsSaveAs()) launchers.saveAs.launch(saveAsName) else viewModel.save()
            }) { Text(stringResource(UiR.string.save), fontWeight = FontWeight.Bold) } } })
    state.exportWarning?.let { action ->
        AlertDialog(
            onDismissRequest = { state.exportWarning = null },
            title = { Text(stringResource(R.string.export_to_non_odf_format)) },
            text = { Text(stringResource(R.string.some_formatting_and_features_may_be_lost)) },
            confirmButton = { TextButton(onClick = { state.exportWarning = null; action() }) { Text(
                stringResource(R.string.export_1),
                fontWeight = FontWeight.Bold) } },
            dismissButton =
                { TextButton(onClick = { state.exportWarning = null }) { Text(stringResource(UiR.string.cancel)) } })
    }
}

/** Share dialog with live roster. */
@Composable
internal fun ShareDialogBody(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isOnline: Boolean,
    onBecameOnline: (String) -> Unit,
) {
    var members by remember { mutableStateOf<List<com.vayunmathur.office.platform.OfficeMember>>(emptyList()) }
    LaunchedEffect(state.showShareDialog) { viewModel.documentMembers { members = it } }
    ShareOnlineDialog(
        deviceId = viewModel.syncDeviceId,
        isOwner = viewModel.currentDocRole() == com.vayunmathur.office.platform.OfficeRoles.OWNER,
        isOnline = isOnline,
        initialName = document.title,
        myRole = viewModel.currentDocRole(),
        members = members,
        shareLink = if (isOnline) viewModel.currentOnlineDocId()?.let { viewModel.shareLinkFor(it) } else null,
        onShare = { recipientId, role, name, cb ->
            viewModel.shareCurrentDocument(recipientId, role, name) { err ->
                if (err == null) {
                    viewModel.documentMembers { members = it } // refresh roster on success
                    viewModel.currentOnlineDocId()?.let { onBecameOnline(it) } // now a cloud doc
                }
                cb(err)
            }
        },
        onSetRole = { memberId, role ->
            viewModel.setMemberRole(memberId, role) {
                viewModel.documentMembers { members = it }
            }
        },
        onRename = { name -> viewModel.renameDocument(name) },
        onTransferOwner = { memberId ->
            viewModel.transferOwnership(memberId) { viewModel.documentMembers { members = it } }
        },
        onComputeCode = { id, cb -> viewModel.securityCodeWith(id, cb) },
        onDismiss = { state.showShareDialog = false }
    )
}

/** Settings dialog overlay. */
@Composable
internal fun SettingsOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    context: android.content.Context,
) {
    if (!state.showSettings) return
    SettingsDialog(
        autoSave = viewModel.getAutoSaveEnabled(context),
        autoSaveInterval = viewModel.getAutoSaveInterval(context),
        defaultFontSize =
            viewModel.getDefaultFontSize(context),
        documentThemeMode = viewModel.getDocumentThemeMode(context),
        onSave = { a, i, f, m -> viewModel.saveSettings(context, a, i, f, m) },
        onDismiss = { state.showSettings = false })
}

/** Run text-color / font-size overlays. */
@Composable
internal fun RunStyleOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
    selEnd: MutableState<Int>,
) {
    if (state.showColorPicker) ColorPickerDialog(
        "Text Color",
        onColorSelected = { c ->
            if (activeRunStart.value >= 0) {
                viewModel.applyRunSpanStyle(
                    activeRunStart.value,
                    activeRunEnd.value,
                    selStart.value,
                    selEnd.value,
                ) { it.copy(color = c) }
            }
        },
        onDismiss = { state.showColorPicker = false },
    )
    if (state.showFontSizePicker) FontSizePickerDialog(
        onSizeSelected = { sz ->
            if (activeRunStart.value >= 0) {
                viewModel.applyRunSpanStyle(
                    activeRunStart.value,
                    activeRunEnd.value,
                    selStart.value,
                    selEnd.value,
                ) { it.copy(fontSize = sz) }
            }
        },
        onDismiss = { state.showFontSizePicker = false },
    )
}

/** Table / link / bookmark / chars / footnote insert overlays. */
@Composable
internal fun InsertOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    focusedPara: Int,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
) {
    if (state.showInsertTable) InsertTableDialog(
        onInsert = { r, c -> viewModel.insertTable(maxOf(0, focusedPara), r, c) },
        onDismiss = { state.showInsertTable = false })
    if (state.showInsertLink) InsertHyperlinkDialog(
        onInsert = { t, u -> viewModel.insertHyperlink(maxOf(0, focusedPara), t, u) },
        onDismiss = { state.showInsertLink = false })
    if (state.showAddBookmark) AddBookmarkDialog(
        onAdd = { viewModel.addBookmark(it, maxOf(0, focusedPara)) },
        onDismiss = { state.showAddBookmark = false })
    CharFootnoteOverlays(state, viewModel, activeRunStart, activeRunEnd, selStart)
}


