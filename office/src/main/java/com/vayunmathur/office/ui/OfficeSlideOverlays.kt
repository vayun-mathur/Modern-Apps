package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.ChartEditorDialog
import com.vayunmathur.office.ui.ColorPickerDialog
import com.vayunmathur.office.ui.ImageCropDialog
import com.vayunmathur.office.platform.OfficeViewModel
import com.vayunmathur.office.platform.insertChart
import com.vayunmathur.office.platform.insertChartIntoSheet
import com.vayunmathur.office.platform.insertChartIntoSlide
import com.vayunmathur.office.platform.replaceTextImage
import com.vayunmathur.office.platform.rotateTextImage
import com.vayunmathur.office.platform.setImageCrop
import com.vayunmathur.office.platform.setSlideBackgroundColor
import com.vayunmathur.office.platform.setSlideNotes
import com.vayunmathur.office.platform.setSlideTransition
import com.vayunmathur.office.platform.slideNotesText
import com.vayunmathur.office.platform.updateChart

import com.vayunmathur.office.platform.save
import com.vayunmathur.office.R
private val NotesFieldHeight = 160.dp

/** Slide + image-crop + chart overlays (split from OfficeDocumentOverlays.kt). */

@Composable
internal fun SlideOverlays(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
    activeSlide: MutableState<Int>,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
    focusedPara: Int,
) {
    SlideNotesOverlay(state, viewModel, isPresentation, activeSlide)
    SlideBackgroundOverlay(state, viewModel, isPresentation, activeSlide)
    SlideTransitionOverlay(state, document, viewModel, isPresentation, activeSlide)
    TextImageCropOverlay(state, document, viewModel, pendingReplace, launchers)
    ChartEditorOverlay(state, document, viewModel, activeCell, activeSlide, focusedPara)
}

/** Speaker-notes editor. */
@Composable
private fun SlideNotesOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    activeSlide: MutableState<Int>,
) {
    if (!state.showSlideNotes || !isPresentation) return
    var text by remember(state.showSlideNotes) { mutableStateOf(viewModel.slideNotesText(activeSlide.value)) }
    AlertDialog(
        onDismissRequest = { state.showSlideNotes = false },
        title = { Text(stringResource(R.string.speaker_notes)) },
        text = { TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.notes_for_this_slide)) },
            modifier = Modifier.height(NotesFieldHeight)) },
        confirmButton = { TextButton(onClick = {
            viewModel.setSlideNotes(activeSlide.value, text)
            state.showSlideNotes = false
        }) { Text(stringResource(UiR.string.save)) } },
        dismissButton =
            { TextButton(onClick = { state.showSlideNotes = false }) { Text(stringResource(UiR.string.cancel)) } }
    )
}

/** Slide background color picker. */
@Composable
private fun SlideBackgroundOverlay(
    state: DocumentOverlayState,
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    activeSlide: MutableState<Int>,
) {
    if (!state.showSlideBackground || !isPresentation) return
    ColorPickerDialog(
        "Slide background",
        onColorSelected = { viewModel.setSlideBackgroundColor(activeSlide.value, it) },
        onDismiss = { state.showSlideBackground = false })
}

/** Slide transition picker. */
@Composable
private fun SlideTransitionOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    isPresentation: Boolean,
    activeSlide: MutableState<Int>,
) {
    if (!state.showSlideTransition || !isPresentation) return
    val slides = (document as? OdfDocument.Presentation)?.slides
    val initialType = slides?.getOrNull(activeSlide.value)?.transitionType ?: "none"
    var type by remember(state.showSlideTransition) { mutableStateOf(initialType) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { state.showSlideTransition = false },
        title = { Text(stringResource(R.string.slide_transition_1)) },
        text = {
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { expanded = true }) { Text(type.replaceFirstChar { it.uppercase() }) }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    TransitionTypes.forEach { t -> DropdownMenuItem(
                        text = { Text(t.replaceFirstChar { ch -> ch.uppercase() }) },
                        onClick = { type = t; expanded = false }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.setSlideTransition(
                    activeSlide.value,
                    type.takeIf { it != "none" },
                    "medium",
                )
                state.showSlideTransition = false
            }) { Text(stringResource(UiR.string.apply)) }
        },
        dismissButton = {
            TextButton(onClick = { state.showSlideTransition = false }) {
                Text(stringResource(UiR.string.cancel))
            }
        },
    )
}

private val TransitionTypes = listOf(
    "none",
    "fade",
    "wipe",
    "dissolve",
    "push",
    "cover",
    "split",
    "blinds",
    "checkerboard",
    "circle",
    "wheel",
)

/** Text-document image crop/replace. */
@Composable
private fun TextImageCropOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    pendingReplace: MutableState<((String, ByteArray) -> Unit)?>,
    launchers: DocumentLaunchers,
) {
    if (state.cropImageBlock < 0) return
    val block = (document as? OdfDocument.TextDocument)?.content?.getOrNull(state.cropImageBlock)
    val img = (block as? OdfContentBlock.Image)?.image
    if (img == null) {
        state.cropImageBlock = -1
        return
    }
    ImageCropDialog(
        image = img,
        onApply = { l, t, r, b ->
            viewModel.setImageCrop(state.cropImageBlock, l, t, r, b)
        },
        onDismiss = { state.cropImageBlock = -1 },
        onRotate = { viewModel.rotateTextImage(state.cropImageBlock, ROTATE_QUARTER_TURNS) },
        onReplace = {
            val bi = state.cropImageBlock
            pendingReplace.value = { n, b -> viewModel.replaceTextImage(bi, n, b) }
            launchers.replaceImage.launch("image/*")
        },
    )
}

/** Chart editor (insert into slide/sheet/text or update). */
@Composable
private fun ChartEditorOverlay(
    state: DocumentOverlayState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activeCell: MutableState<Triple<Int, Int, Int>?>,
    activeSlide: MutableState<Int>,
    focusedPara: Int,
) {
    if (!state.showChartEditor) return
    val textDoc = document as? OdfDocument.TextDocument
    val chartBlock = if (!state.chartForSlide && state.editingChartBlock >= 0) {
        textDoc?.content?.getOrNull(state.editingChartBlock) as? OdfContentBlock.Chart
    } else {
        null
    }
    ChartEditorDialog(
        initial = chartBlock?.chart,
        onConfirm = { ch ->
            when {
                state.chartForSlide -> viewModel.insertChartIntoSlide(activeSlide.value, ch)
                state.chartForSheet -> viewModel.insertChartIntoSheet(activeCell.value?.first ?: 0, ch)
                state.editingChartBlock >= 0 -> viewModel.updateChart(state.editingChartBlock, ch)
                else -> if (focusedPara >= 0) viewModel.insertChart(focusedPara, ch)
            }
        },
        onDismiss = {
            state.showChartEditor = false
            state.editingChartBlock = -1
            state.chartForSlide = false
            state.chartForSheet = false
        },
    )
}
