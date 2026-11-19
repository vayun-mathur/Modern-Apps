package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.office.R

import com.vayunmathur.office.Navigation
@Composable
fun PresentationView(
    doc: OdfDocument.Presentation,
    isEditMode: Boolean = false,
    onAddSlide: (Int) -> Unit = {},
    onDeleteSlide: (Int) -> Unit = {},
    onDuplicateSlide: (Int) -> Unit = {},
    onMoveSlideUp: (Int) -> Unit = {},
    onMoveSlideDown: (Int) -> Unit = {},
    onElementTextChange: (slideIndex: Int, elementIndex: Int, text: String) -> Unit = { _, _, _ -> },
    onAddTextBox: (Int) -> Unit = {},
    onElementBoundsChange: (Int, Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _, _ -> },
    onDeleteElement: (Int, Int) -> Unit = { _, _ -> },
    selectedElement: Int = -1,
    onSlideChange: (Int) -> Unit = {},
    onElementSelected: (Int, Int) -> Unit = { _, _ -> },
    onCropImage: (Int, Int) -> Unit = { _, _ -> }
) {
    if (doc.slides.isEmpty()) { Text(
        stringResource(R.string.empty_presentation),
        modifier = Modifier.padding(16.dp)); return }

    var currentSlide by remember { mutableIntStateOf(0) }
    var showGoToSlide by remember { mutableStateOf(false) }
    var showSlideshow by remember { mutableStateOf(false) }
    var dragAccumulator by remember { mutableFloatStateOf(0f) }
    currentSlide = currentSlide.coerceIn(0, doc.slides.size - 1)
    LaunchedEffect(currentSlide) { onSlideChange(currentSlide); onElementSelected(currentSlide, -1) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Main slide with swipe gesture
        Box(
            modifier = Modifier.weight(1f).pointerInput(doc.slides.size) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccumulator < -100 && currentSlide < doc.slides.size - 1) currentSlide++
                        else if (dragAccumulator > 100 && currentSlide > 0) currentSlide--
                        dragAccumulator = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragAccumulator += dragAmount }
                )
            }
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                item {
                    SlideCard(
                        slide = doc.slides[currentSlide],
                        editMode = isEditMode,
                        selectedIndex = selectedElement,
                        onSelect = { onElementSelected(currentSlide, it) },
                        onElementTextChange = { ei, t -> onElementTextChange(currentSlide, ei, t) },
                        onBoundsChange = { ei, x, y, w, h -> onElementBoundsChange(currentSlide, ei, x, y, w, h) },
                        onDelete = { ei -> onDeleteElement(currentSlide, ei); onElementSelected(currentSlide, -1) },
                        onCropImage = { ei -> onCropImage(currentSlide, ei) }
                    )
                }
            }
        }

        // Slide editing controls
        if (isEditMode) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAddSlide(currentSlide) }) { Text(stringResource(R.string.slide_1)) }
                TextButton(onClick = { onAddTextBox(currentSlide) }) { Text(stringResource(R.string.text)) }
                TextButton(onClick = { onDuplicateSlide(currentSlide) }) { Text(stringResource(R.string.dup)) }
                TextButton(onClick = { onMoveSlideUp(currentSlide); if (currentSlide > 0) currentSlide-- }) { Text("↑") }
                TextButton(onClick = { onMoveSlideDown(currentSlide); if (currentSlide < doc.slides.size - 1) currentSlide++ }) { Text("↓") }
                if (doc.slides.size > 1) TextButton(onClick = { onDeleteSlide(currentSlide); currentSlide = minOf(
                    currentSlide,
                    doc.slides.size - 2).coerceAtLeast(0) }) {
                    Text(stringResource(UiR.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // Slide thumbnail strip
        if (doc.slides.size > 1) {
            HorizontalDivider()
            LazyRow(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(doc.slides.size) { index ->
                    SlideThumbnail(doc.slides[index], index, index == currentSlide) { currentSlide = index }
                }
            }
        }

        // Navigation bar
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    onClick = { if (currentSlide > 0) currentSlide-- },
                    enabled = currentSlide > 0) { Text(stringResource(R.string.prev)) }
                TextButton(onClick = { showSlideshow = true }) { Text(stringResource(UiR.string.play)) }
                TextButton(onClick = { showGoToSlide = true }) {
                    Text(
                        stringResource(R.string.slide_of, currentSlide + 1, doc.slides.size),
                        style = MaterialTheme.typography.titleSmall)
                }
                TextButton(
                    onClick = { if (currentSlide < doc.slides.size - 1) currentSlide++ },
                    enabled = currentSlide < doc.slides.size - 1) { Text(stringResource(UiR.string.next)) }
            }
        }
    }

    if (showGoToSlide) GoToSlideDialog(
        doc.slides.size,
        onGo = { currentSlide = it },
        onDismiss = { showGoToSlide = false })
    if (showSlideshow) SlideshowDialog(
        doc.slides,
        currentSlide,
        onSlideChange = { currentSlide = it },
        onDismiss = { showSlideshow = false })
}

@Composable
private fun SlideshowDialog(
    slides: List<OdfSlide>,
    startIndex: Int,
    onSlideChange: (Int) -> Unit,
    onDismiss: () -> Unit) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, slides.size - 1)) }
    // Slides may shrink via a live merge while the slideshow is open.
    if (slides.isNotEmpty()) index = index.coerceIn(0, slides.size - 1)
    Dialog(
        onDismissRequest = { onSlideChange(index); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val slide = slides[index]
            val (refW, refH) = slideBounds(slide)
            Box(Modifier.fillMaxWidth().aspectRatio((refW / refH).coerceIn(
                0.5f,
                3f)).align(Alignment.Center).background(Color.White)) {
                SlideCanvas(slide, refW, refH)
            }
            // Tap zones
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index > 0) { index--; onSlideChange(index) } })
                Box(Modifier.weight(1f).fillMaxHeight().clickable { if (index < slides.size - 1) { index++; onSlideChange(index) } else { onSlideChange(index); onDismiss() } })
            }
            Text(
                "${index + 1} / ${slides.size}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp))
            TextButton(
                onClick = { onSlideChange(index); onDismiss() },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp))
            { Text(stringResource(R.string.close_search), color = Color.White) }
        }
    }
}

@Composable
private fun SlideThumbnail(slide: OdfSlide, index: Int, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        border = if (isSelected) androidx.compose.foundation.BorderStroke(
            2.dp,
            MaterialTheme.colorScheme.primary) else null
    ) {
        Box(Modifier.fillMaxSize().then(slide.backgroundColor?.let { Modifier.background(Color(it.toInt())) } ?: Modifier).padding(4.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val firstText = slide.elements.firstNotNullOfOrNull { el ->
                    when (el) { is OdfSlideElement.Frame -> el.frame.paragraphs.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30); is OdfSlideElement.Shape -> el.shape.text.firstOrNull()?.spans?.joinToString("") { it.text }?.take(30) }
                }
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                if (firstText != null) Text(firstText, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SlideCard(
    slide: OdfSlide,
    editMode: Boolean = false,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            slide.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        val (refW, refH) = slideBounds(slide)
        val ratio = (refW / refH).coerceIn(0.5f, 3f)
        if (editMode) {
            // Edit mode: render the canvas in a non-clipping Box so selection handles that sit at
            // negative offsets near the slide edges aren't cut off by the Card's clip. (C1)
            Box(Modifier.fillMaxWidth().aspectRatio(ratio).padding(horizontal = 8.dp)) {
                Surface(
                    Modifier.matchParentSize(),
                    shape = RoundedCornerShape(4.dp),
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface) {}
                SlideCanvas(
                    slide,
                    refW,
                    refH,
                    true,
                    selectedIndex,
                    onSelect,
                    onElementTextChange,
                    onBoundsChange,
                    onDelete,
                    onCropImage)
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(ratio).padding(horizontal = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                SlideCanvas(
                    slide,
                    refW,
                    refH,
                    false,
                    selectedIndex,
                    onSelect,
                    onElementTextChange,
                    onBoundsChange,
                    onDelete,
                    onCropImage)
            }
        }
        if (slide.notes.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(start = 8.dp))
            { Text(if (expanded) stringResource(R.string.hide_notes) else stringResource(R.string.speaker_notes)) }
            if (expanded) Column(modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 4.dp)) { for (note in slide.notes) ParagraphView(note) }
        }
    }
}

private fun slideBounds(slide: OdfSlide): Pair<Float, Float> {
    var maxR = 0f; var maxB = 0f
    for (el in slide.elements) {
        val (x, y, w, h) = when (el) {
            is OdfSlideElement.Frame -> listOf(el.frame.x, el.frame.y, el.frame.width, el.frame.height)
            is OdfSlideElement.Shape -> listOf(el.shape.x, el.shape.y, el.shape.width, el.shape.height)
        }
        maxR = maxOf(maxR, x + w); maxB = maxOf(maxB, y + h)
    }
    val refW = maxOf(maxR, 1058f)
    val refH = maxOf(maxB, 794f)
    return refW to refH
}

@Composable
private fun SlideCanvas(
    slide: OdfSlide,
    refW: Float,
    refH: Float,
    editMode: Boolean = false,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onElementTextChange: (Int, String) -> Unit = { _, _ -> },
    onBoundsChange: (Int, Float, Float, Float, Float) -> Unit = { _, _, _, _, _ -> },
    onDelete: (Int) -> Unit = {},
    onCropImage: (Int) -> Unit = {}
) {
    FloatingElementLayer(
        elements = slide.elements, refW = refW, refH = refH,
        modifier = Modifier.fillMaxSize(),
        editMode = editMode, selectedIndex = selectedIndex, keyPrefix = slide.name,
        backgroundColor = slide.backgroundColor,
        onSelect = onSelect, onElementTextChange = onElementTextChange,
        onBoundsChange = onBoundsChange, onDelete = onDelete, onCropImage = onCropImage
    )
}
