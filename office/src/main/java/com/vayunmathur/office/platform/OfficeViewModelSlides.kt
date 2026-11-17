package com.vayunmathur.office.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.bounds
import com.vayunmathur.library.ui.odf.setElementBounds
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.office.R
import kotlin.io.encoding.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// --- Presentation / slides / floating objects (split from OfficeViewModel.kt for file length) ---

fun OfficeViewModel.addSlide(afterIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    slides.add(afterIndex + 1, OdfSlide(
        name = "Slide ${slides.size + 1}",
        elements = listOf(OdfSlideElement.Frame(OdfFrame(
            x = 50f, y = 50f, width = 600f, height = 100f,
            paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New Slide", bold = true, fontSize = 28f))))
        )))
    ))
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.deleteSlide(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    if (doc.slides.size <= 1) return
    val slides = doc.slides.toMutableList()
    slides.removeAt(index)
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.duplicateSlide(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    slides.add(index + 1, slides[index].copy(name = "${slides[index].name} (copy)"))
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.moveSlideUp(index: Int) {
    if (index <= 0) return
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val item = slides.removeAt(index)
    slides.add(index - 1, item)
    updateDocument(doc.copy(slides = slides))
}

fun OfficeViewModel.moveSlideDown(index: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    if (index >= doc.slides.size - 1) return
    val slides = doc.slides.toMutableList()
    val item = slides.removeAt(index)
    slides.add(index + 1, item)
    updateDocument(doc.copy(slides = slides))
}

/** Edits the text of a slide element (frame or shape), preserving the first span's formatting (I62). */
fun OfficeViewModel.updateSlideElementText(slideIndex: Int, elementIndex: Int, newText: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
        val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
        val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
        return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
    }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape
            val t = rebuild(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t)
                is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t)
                is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Adds an empty text box to a slide (I62). */
fun OfficeViewModel.addTextBoxToSlide(slideIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val frame = OdfFrame(
        x = 60f, y = 200f + slide.elements.size * 20f, width = 600f, height = 80f,
        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text = "New text", fontSize = 20f))))
    )
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides))
}

/** Adds a shape to a slide at a default centered rect. kind = "rect"|"ellipse"|"line". (Phase 3) */
fun OfficeViewModel.addShapeToSlide(slideIndex: Int, kind: String) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val x = 329f; val y = 247f; val w = 400f; val h = 300f
    val shape: OdfShape = when (kind) {
        "ellipse" -> OdfShape.Ellipse(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
        "line" -> OdfShape.Line(x, y, w, 0f, strokeColor = 0xFF333333L, strokeWidth = 2f, x2 = x + w, y2 = y)
        else -> OdfShape.Rect(x, y, w, h, fillColor = 0xFFB3D1FFL, strokeColor = 0xFF1F6FC0L, strokeWidth = 2f)
    }
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Shape(shape))
    updateDocument(doc.copy(slides = slides))
}

/** Inserts an image as a floating frame on a slide. (Phase 3) */
fun OfficeViewModel.insertImageIntoSlide(slideIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val frame = OdfFrame(x = 300f, y = 200f, width = 400f, height = 300f, paragraphs = emptyList(),
        image = OdfImage(path = path, imageData = bytes))
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
}

/** Inserts a chart as a floating frame on a slide. (Phase 3) */
fun OfficeViewModel.insertChartIntoSlide(slideIndex: Int, chart: OdfChart) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val frame = OdfFrame(x = 250f, y = 180f, width = 520f, height = 360f, paragraphs = emptyList(), chart = chart)
    slides[slideIndex] = slide.copy(elements = slide.elements + OdfSlideElement.Frame(frame))
    updateDocument(doc.copy(slides = slides))
}

// --- Slide chrome (background / transition / speaker notes) ---

internal fun OfficeViewModel.mutateSlide(slideIndex: Int, transform: (OdfSlide) -> OdfSlide) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    slides[slideIndex] = transform(slide)
    updateDocument(doc.copy(slides = slides))
}

/** Sets speaker notes for a slide (one paragraph per line; blank clears them). */
fun OfficeViewModel.setSlideNotes(slideIndex: Int, text: String) = mutateSlide(slideIndex) { slide ->
    slide.copy(notes = if (text.isBlank()) emptyList() else text.split("\n").map { OdfParagraph(listOf(OdfSpan(it))) })
}

/** Current speaker-notes text for a slide, joined by newlines. */
fun OfficeViewModel.slideNotesText(slideIndex: Int): String {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return ""
    val notes = doc.slides.getOrNull(slideIndex)?.notes ?: return ""
    return notes.joinToString("\n") { p -> p.spans.joinToString("") { it.text } }
}

/** Sets (or clears with null) the solid background color of a slide. */
fun OfficeViewModel.setSlideBackgroundColor(
    slideIndex: Int,
    color: Long?) = mutateSlide(slideIndex) { it.copy(backgroundColor = color) }

/** Sets the slide transition type (e.g. "fade","wipe","dissolve"; null clears) and speed. */
fun OfficeViewModel.setSlideTransition(slideIndex: Int, type: String?, speed: String?) =
    mutateSlide(slideIndex) { it.copy(transitionType = type, transitionSpeed = speed) }

/** Rotates ANY slide element by delta degrees: shapes rotate via their angle, image frames via the image. */
fun OfficeViewModel.setSlideElementRotation(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = rotateElement(el, deltaDegrees)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

internal fun OfficeViewModel.rotateElement(el: OdfSlideElement, delta: Float): OdfSlideElement = when (el) {
    is OdfSlideElement.Frame -> el.frame.image?.let {
        val rotated = (it.rotationDegrees + delta) % FULL_ROTATION
        OdfSlideElement.Frame(el.frame.copy(image = it.copy(rotationDegrees = rotated)))
    } ?: el
    is OdfSlideElement.Shape -> {
        val s = el.shape; val nd = (s.rotationDegrees + delta) % 360f
        OdfSlideElement.Shape(when (s) {
            is OdfShape.Rect -> s.copy(rotationDegrees = nd)
            is OdfShape.Ellipse -> s.copy(rotationDegrees = nd)
            is OdfShape.Line -> s.copy(rotationDegrees = nd)
            is OdfShape.CustomShape -> s.copy(rotationDegrees = nd)
            is OdfShape.Polyline -> s.copy(rotationDegrees = nd)
        })
    }
}

// --- Spreadsheet cell comments & sizing ---

/** Sets (or clears with blank text) a comment/note on a cell. */
internal fun OfficeViewModel.setSlideElementTextOn(el: OdfSlideElement, newText: String): OdfSlideElement {
    fun rebuild(old: List<OdfParagraph>): List<OdfParagraph> {
        val template = old.firstOrNull()?.spans?.firstOrNull()?.copy(text = "") ?: OdfSpan(text = "")
        val style = old.firstOrNull()?.style ?: ParagraphStyle.BODY
        return newText.split("\n").map { line -> OdfParagraph(listOf(template.copy(text = line)), style = style) }
    }
    return when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = rebuild(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape; val t = rebuild(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
}

internal fun OfficeViewModel.elementParas(el: OdfSlideElement): List<OdfParagraph> = when (el) {
    is OdfSlideElement.Frame -> el.frame.paragraphs
    is OdfSlideElement.Shape -> el.shape.text
}

internal fun OfficeViewModel.mutateSlideElementSpans(
    slideIndex: Int,
    elementIndex: Int,
    transform: (OdfSpan) -> OdfSpan) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun map(ps: List<OdfParagraph>) = ps.map { p -> p.copy(spans = p.spans.map(transform)) }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val s = el.shape; val t = map(s.text)
            OdfSlideElement.Shape(when (s) {
                is OdfShape.Rect -> s.copy(text = t); is OdfShape.Ellipse -> s.copy(text = t)
                is OdfShape.Line -> s.copy(text = t); is OdfShape.CustomShape -> s.copy(text = t)
                is OdfShape.Polyline -> s.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

internal fun OfficeViewModel.firstSpan(slideIndex: Int, elementIndex: Int): OdfSpan? {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return null
    val el = doc.slides.getOrNull(slideIndex)?.elements?.getOrNull(elementIndex) ?: return null
    return elementParas(el).firstOrNull()?.spans?.firstOrNull()
}

fun OfficeViewModel.toggleSlideElementBold(
    s: Int,
    e: Int) { val cur = firstSpan(s, e)?.bold == true; mutateSlideElementSpans(s, e) { it.copy(bold = !cur) } }
fun OfficeViewModel.toggleSlideElementItalic(
    s: Int,
    e: Int) { val cur = firstSpan(s, e)?.italic == true; mutateSlideElementSpans(s, e) { it.copy(italic = !cur) } }
fun OfficeViewModel.toggleSlideElementUnderline(
    s: Int,
    e: Int)
{ val cur = firstSpan(s, e)?.underline == true; mutateSlideElementSpans(s, e) { it.copy(underline = !cur) } }
fun OfficeViewModel.setSlideElementColor(
    s: Int,
    e: Int,
    color: Long?) { mutateSlideElementSpans(s, e) { it.copy(color = color) } }

/** Sets paragraph alignment for all paragraphs in a slide element. */
fun OfficeViewModel.setSlideElementAlignment(
    slideIndex: Int,
    elementIndex: Int,
    alignment: androidx.compose.ui.text.style.TextAlign?) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    fun map(ps: List<OdfParagraph>) = ps.map { it.copy(alignment = alignment) }
    elements[elementIndex] = when (el) {
        is OdfSlideElement.Frame -> OdfSlideElement.Frame(el.frame.copy(paragraphs = map(el.frame.paragraphs)))
        is OdfSlideElement.Shape -> {
            val sh = el.shape; val t = map(sh.text)
            OdfSlideElement.Shape(when (sh) {
                is OdfShape.Rect -> sh.copy(text = t); is OdfShape.Ellipse -> sh.copy(text = t)
                is OdfShape.Line -> sh.copy(text = t); is OdfShape.CustomShape -> sh.copy(text = t)
                is OdfShape.Polyline -> sh.copy(text = t)
            })
        }
    }
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Moves/resizes a slide element (frame or shape). Coordinates are px@96. (Phase 1) */
fun OfficeViewModel.setSlideElementBounds(slideIndex: Int, elementIndex: Int, x: Float, y: Float, w: Float, h: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = setElementBounds(el, x, y, w, h)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Removes a slide element. (Phase 1) */
fun OfficeViewModel.deleteSlideElement(slideIndex: Int, elementIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    if (elementIndex !in slide.elements.indices) return
    val elements = slide.elements.toMutableList()
    elements.removeAt(elementIndex)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Duplicates a slide element, offset slightly so it's visible. (C1) */
fun OfficeViewModel.duplicateSlideElement(slideIndex: Int, elementIndex: Int) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) ?: return
    val b = el.bounds()
    val copy = setElementBounds(el, b[0] + 20f, b[1] + 20f, b[2], b[3])
    val elements = slide.elements.toMutableList()
    elements.add(elementIndex + 1, copy)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Reorders a slide element in document order (= ODF render z-order). delta<0 = back, delta>0 = front. (C1) */
fun OfficeViewModel.reorderSlideElement(slideIndex: Int, elementIndex: Int, toFront: Boolean) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    if (elementIndex !in slide.elements.indices) return
    val elements = slide.elements.toMutableList()
    val item = elements.removeAt(elementIndex)
    if (toFront) elements.add(item) else elements.add(0, item)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Rotates an image element on a slide by the given delta degrees. (C4) */
