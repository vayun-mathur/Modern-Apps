package com.vayunmathur.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.platform.SafeAnnotation
import com.vayunmathur.pdf.platform.SafeFormField
import com.vayunmathur.pdf.platform.SafeLink
import com.vayunmathur.pdf.platform.SafePdfDocument
import kotlinx.coroutines.CoroutineScope

/** One page in the lazy list: decode + annotations/forms/links, then the read or edit overlays. */
@Composable
internal fun SafePdfPageItem(
    document: SafePdfDocument,
    index: Int,
    version: Int,
    ocr: OcrEngine?,
    editMode: Boolean,
    tool: EditTool,
    shape: ShapeKind,
    markup: MarkupKind,
    color: Color,
    strokeWidth: Float,
    selected: Long?,
    highlights: List<androidx.compose.ui.geometry.Rect>,
    currentHighlight: androidx.compose.ui.geometry.Rect?,
    scope: CoroutineScope,
    onSelect: (Long?) -> Unit,
    onEdited: () -> Unit,
    onCreated: (Long) -> Unit,
    onMoved: (Long, List<Float>, List<Float>) -> Unit,
    onFormEdited: () -> Unit,
    onPageWidth: (Float) -> Unit = {},
    textSession: TextSession?,
    onStartText: (TextSession) -> Unit,
    onTextChange: (TextFieldValue) -> Unit,
    onCommitText: () -> Unit,
    onRequestImage: (Offset) -> Unit,
    onRequestNote: (Offset) -> Unit,
    onRequestCallout: (Offset, Offset) -> Unit,
    polyDraft: PolyDraft?,
    onAddPolyPoint: (Offset) -> Unit,
    onLinkPage: (Int) -> Unit,
) {
    val pageLoad by produceState<SafePdfDocument.PageLoad?>(null, document, index, version) {
        value = document.renderPageResult(index)
    }
    val annotations by produceState(emptyList<SafeAnnotation>(), document, index, version) {
        value = if (editMode) document.annotations(index) else emptyList()
    }
    val formFields by produceState(emptyList<SafeFormField>(), document, index, version) {
        value = if (editMode) document.formFields(index) else emptyList()
    }
    val links by produceState(emptyList<SafeLink>(), document, index, version, editMode) {
        value = if (!editMode) document.links(index) else emptyList()
    }

    val current = (pageLoad as? SafePdfDocument.PageLoad.Decoded)?.page
    val failureReason = (pageLoad as? SafePdfDocument.PageLoad.Failed)?.reason

    // Report this page's width so the viewer can raise the zoom cap for large pages.
    LaunchedEffect(current?.width) {
        current?.width?.let { if (it > 0f) onPageWidth(it) }
    }

    SafePdfPageCanvas(
        current,
        placeholderRatio = document.knownAspectRatio(index),
        failureReason = failureReason,
    ) { cw, ch, scale ->
        // Non-null inside the overlay slot: SafePdfPageCanvas only invokes it once the page
        // has been decoded.
        val decoded = current!!

        fun toPage(o: Offset) = Offset(o.x / scale, (ch - o.y) / scale)

        if (highlights.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                for (r in highlights) {
                    val isCurrent = r == currentHighlight
                    drawRect(
                        color = if (isCurrent) Color(0xAAFF9800) else Color(0x66FFEB3B),
                        topLeft = Offset(r.left * scale, ch - r.top * scale),
                        size = Size((r.right - r.left) * scale, (r.top - r.bottom) * scale),
                    )
                }
            }
        }

        if (!editMode) {
            NonEditOverlay(
                page = decoded,
                links = links,
                ch = ch,
                scale = scale,
                ocr = ocr,
                onLinkPage = onLinkPage,
            )
        }

        if (editMode) {
            EditOverlay(
                annotations = annotations,
                selected = selected,
                tool = tool,
                shape = shape,
                markup = markup,
                color = color,
                strokeWidth = strokeWidth,
                ch = ch,
                scale = scale,
                toPage = ::toPage,
                document = document,
                index = index,
                scope = scope,
                onSelect = onSelect,
                onEdited = onEdited,
                onCreated = onCreated,
                onMoved = onMoved,
                onStartText = onStartText,
                onRequestImage = onRequestImage,
                onRequestNote = onRequestNote,
                onRequestCallout = onRequestCallout,
                polyDraft = polyDraft,
                onAddPolyPoint = onAddPolyPoint,
            )
            FormFieldOverlay(
                fields = formFields,
                ch = ch,
                scale = scale,
                document = document,
                index = index,
                scope = scope,
                onEdited = onFormEdited,
            )

            // Inline text editing: a live text field drawn on the page.
            if (textSession != null) {
                val density = LocalDensity.current
                val focus = remember(textSession.annotId, textSession.origin) { FocusRequester() }
                LaunchedEffect(textSession.annotId, textSession.origin) { focus.requestFocus() }
                val leftDp = with(density) { (textSession.origin.x * scale).toDp() }
                val topDp = with(density) { (ch - textSession.origin.y * scale).toDp() }
                BasicTextField(
                    value = textSession.value,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .offset(x = leftDp, y = topDp)
                        .widthIn(min = 48.dp)
                        .focusRequester(focus)
                        .onFocusChanged { if (!it.isFocused) onCommitText() }
                        .background(Color(0x33448AFF)),
                    textStyle = TextStyle(
                        color = Color(textSession.color),
                        fontSize = with(density) { (textSession.size * scale).toSp() },
                    ),
                    cursorBrush = SolidColor(Color(textSession.color)),
                )
            }
        }
    }
}
