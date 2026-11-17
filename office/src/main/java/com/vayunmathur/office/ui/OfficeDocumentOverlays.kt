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
/**
 * Dialog/show flags owned by [DocumentScreen].
 *
 * Plain holder with snapshot-state delegates, kept across recompositions via
 * [rememberDocumentOverlayState]; identical semantics to the `var x by remember {}`
 * declarations it replaces (split for file length, behavior identical).
 *
 * Subclassed by [DocumentScreenState], which adds the selection and chrome state.
 */
open class DocumentOverlayState {
    var showMetadata by mutableStateOf(false)
    var showShareDialog by mutableStateOf(false)
    var showEnableOnlineDialog by mutableStateOf(false)
    val showUnsavedDialogState = mutableStateOf(false)
    var showUnsavedDialog by showUnsavedDialogState
    var showSettings by mutableStateOf(false)
    val showColorPickerState = mutableStateOf(false)
    var showColorPicker by showColorPickerState
    val showFontSizePickerState = mutableStateOf(false)
    var showFontSizePicker by showFontSizePickerState
    val showInsertTableState = mutableStateOf(false)
    var showInsertTable by showInsertTableState
    var showInsertLink by mutableStateOf(false)
    var showAddBookmark by mutableStateOf(false)
    var showSpecialChars by mutableStateOf(false)
    var showFootnote by mutableStateOf(false)
    var showComment by mutableStateOf(false)
    var showComments by mutableStateOf(false)
    var showChanges by mutableStateOf(false)
    var showPageSetup by mutableStateOf(false)
    var showHeaderFooter by mutableStateOf(false)
    val showCellTextColorState = mutableStateOf(false)
    var showCellTextColor by showCellTextColorState
    val showCellBgColorState = mutableStateOf(false)
    var showCellBgColor by showCellBgColorState
    val showCellBorderColorState = mutableStateOf(false)
    var showCellBorderColor by showCellBorderColorState
    val showSlideTextColorState = mutableStateOf(false)
    var showSlideTextColor by showSlideTextColorState
    val showSlideFillColorState = mutableStateOf(false)
    var showSlideFillColor by showSlideFillColorState
    val showSlideStrokeColorState = mutableStateOf(false)
    var showSlideStrokeColor by showSlideStrokeColorState
    val showCellCommentState = mutableStateOf(false)
    var showCellComment by showCellCommentState
    val showCellResizeState = mutableStateOf(false)
    var showCellResize by showCellResizeState
    val showSlideNotesState = mutableStateOf(false)
    var showSlideNotes by showSlideNotesState
    val showSlideBackgroundState = mutableStateOf(false)
    var showSlideBackground by showSlideBackgroundState
    val showSlideTransitionState = mutableStateOf(false)
    var showSlideTransition by showSlideTransitionState
    val showChartEditorState = mutableStateOf(false)
    var showChartEditor by showChartEditorState
    val editingChartBlockState = mutableIntStateOf(-1)
    var editingChartBlock by editingChartBlockState
    val chartForSlideState = mutableStateOf(false)
    var chartForSlide by chartForSlideState
    val chartForSheetState = mutableStateOf(false)
    var chartForSheet by chartForSheetState
    val cropImageBlockState = mutableIntStateOf(-1)
    var cropImageBlock by cropImageBlockState
    val cropSlideTargetState: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
    var cropSlideTarget by cropSlideTargetState
    val cropSheetTargetState: MutableState<Pair<Int, Int>?> = mutableStateOf(null)
    var cropSheetTarget by cropSheetTargetState
    var exportWarning by mutableStateOf<(() -> Unit)?>(null)
}

@Composable
internal fun rememberDocumentOverlayState(): DocumentOverlayState = remember { DocumentOverlayState() }

internal const val MARGIN_NARROW_CM = 1.27f
internal const val MARGIN_NARROW_MAX = 1.5f
internal const val MARGIN_NORMAL_CM = 2f
internal const val MARGIN_WIDE_MIN = 2.3f
internal const val MARGIN_WIDE_CM = 2.54f
private val CommentFieldHeight = 120.dp
private val ChartPickerHeight = 160.dp
private val DialogSpacing = 8.dp
private const val DATE_PREFIX_LENGTH = 10

internal const val SHARED_DATE_PREFIX_LENGTH = 10
internal const val ROTATE_QUARTER_TURNS = 90f

/**
 * All trailing `if (showX)` dialogs of [DocumentScreen], hoisted verbatim.
 *
 * Dialog flags live in [DocumentOverlayState] (via [DocumentScreenState]); selection state
 * shared with the scaffold body arrives as [MutableState] params. Behavior identical.
 */
@Composable
fun DocumentOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    isOnline: Boolean,
    focusedPara: Int,
    saveAsName: String,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
    activeSlide: MutableState<Int>,
    activeSlideEl: MutableState<Int>,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
    selEnd: MutableState<Int>,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    listState: LazyListState,
    scope: CoroutineScope,
    launchers: DocumentLaunchers,
    onBecameOnline: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    if (state.showMetadata) MetadataDialog(
        metadata = document.metadata,
        onSave = { m -> viewModel.updateMetadata { m } },
        onDismiss = { state.showMetadata = false })
    LaunchedEffect(Unit) { viewModel.initSync() }
    ShareOverlays(
        state, document, viewModel, isOnline, saveAsName, launchers, onBecameOnline, onBack)
    SettingsOverlay(state, viewModel, context)
    RunStyleOverlays(state, viewModel, activeRunStart, activeRunEnd, selStart, selEnd)
    InsertOverlays(state, viewModel, focusedPara, activeRunStart, activeRunEnd, selStart)
    CommentOverlaysHost(state, document, viewModel, focusedPara, listState, scope)
    CellOverlays(state, viewModel, activeCell, activeSlide, activeSlideEl)
    SlideOverlays(
        state, document, viewModel, isPresentation, activeCell, activeSlide,
        pendingReplace, launchers, focusedPara)
    SlideCropOverlay(state, document, viewModel, pendingReplace, launchers)
    SheetCropOverlay(state, document, viewModel, pendingReplace, launchers)
}









/** Special-chars + footnote overlays. */
@Composable
internal fun CharFootnoteOverlays(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    activeRunStart: MutableState<Int>,
    activeRunEnd: MutableState<Int>,
    selStart: MutableState<Int>,
) {
    if (state.showSpecialChars) SpecialCharsDialog(
        onPick = { ch ->
            if (activeRunStart.value >= 0) {
                viewModel.insertTextInRun(activeRunStart.value, activeRunEnd.value, selStart.value, ch)
            }
        },
        onDismiss = { state.showSpecialChars = false },
    )
    if (state.showFootnote) FootnoteDialog(
        onAdd = { body ->
            if (activeRunStart.value >= 0) {
                viewModel.insertFootnote(activeRunStart.value, activeRunEnd.value, selStart.value, body)
            }
        },
        onDismiss = { state.showFootnote = false },
    )
}
