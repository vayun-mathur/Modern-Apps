package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan

/**
 * Image operations for slides/sheets/text (split from OfficeViewModelSlides.kt for file length).
 * Behavior identical, call sites unchanged.
 */

internal const val FULL_ROTATION = 360f

fun OfficeViewModel.rotateSlideImage(slideIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val img = el.frame.image ?: return
    val rotated = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % FULL_ROTATION)
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = rotated))
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Rotates a text-document image block by delta degrees. (C4) */
fun OfficeViewModel.rotateTextImage(blockIndex: Int, deltaDegrees: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val content = doc.content.toMutableList()
    val rotated = (block.image.rotationDegrees + deltaDegrees) % FULL_ROTATION
    content[blockIndex] =
        OdfContentBlock.Image(block.image.copy(rotationDegrees = rotated))
    updateDocument(doc.copy(content = content))
}

/** Rotates an image element on a sheet's floating layer by delta degrees. (C4) */
fun OfficeViewModel.rotateSheetImage(sheetIndex: Int, elementIndex: Int, deltaDegrees: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        val el = list.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return@mutateSheetFloating list
        val img = el.frame.image ?: return@mutateSheetFloating list
        val rotated = img.copy(rotationDegrees = (img.rotationDegrees + deltaDegrees) % FULL_ROTATION)
        list.toMutableList().also { it[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = rotated)) }
    }
}

/** Replaces a text-document image's bytes with a new picture, resetting crop/rotation. (C4) */
fun OfficeViewModel.replaceTextImage(blockIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.TextDocument ?: return
    val block = doc.content.getOrNull(blockIndex) as? OdfContentBlock.Image ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val content = doc.content.toMutableList()
    content[blockIndex] = OdfContentBlock.Image(block.image.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    ))
    updateDocument(doc.copy(content = content, images = doc.images + (path to bytes)))
}

/** Replaces a slide image element's bytes with a new picture. (C4) */
fun OfficeViewModel.replaceSlideImage(slideIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val el = slide.elements.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val frameImage = el.frame.image ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    )))
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides, images = doc.images + (path to bytes)))
}

/** Replaces a sheet floating image element's bytes with a new picture. (C4) */
fun OfficeViewModel.replaceSheetImage(sheetIndex: Int, elementIndex: Int, fileName: String, bytes: ByteArray) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Spreadsheet ?: return
    val path = uniqueImagePath(doc.images.keys, fileName)
    val sheets = doc.sheets.toMutableList()
    val sheet = sheets.getOrNull(sheetIndex) ?: return
    val el = sheet.floating.getOrNull(elementIndex) as? OdfSlideElement.Frame ?: return
    val frameImage = el.frame.image ?: return
    val floating = sheet.floating.toMutableList()
    floating[elementIndex] = OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
        path = path, imageData = bytes, naturalWidthPx = 0f, naturalHeightPx = 0f,
        cropLeftPct = 0f, cropTopPct = 0f, cropRightPct = 0f, cropBottomPct = 0f, rotationDegrees = 0f
    )))
    sheets[sheetIndex] = sheet.copy(floating = floating)
    updateDocument(doc.copy(sheets = sheets, images = doc.images + (path to bytes)))
}

/** Sets crop insets on an image frame element of a slide. (Phase 5) */
fun OfficeViewModel.setSlideImageCrop(
    slideIndex: Int,
    elementIndex: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    elements[elementIndex] = cropElementImage(elements.getOrNull(elementIndex) ?: return, left, top, right, bottom)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Sets crop insets on an image frame element of a sheet's floating layer. (Phase 5) */
fun OfficeViewModel.setSheetImageCrop(
    sheetIndex: Int,
    elementIndex: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float) {
    mutateSheetFloating(sheetIndex) { list ->
        if (elementIndex !in list.indices) list
        else list.toMutableList().also { it[elementIndex] = cropElementImage(
            it[elementIndex],
            left,
            top,
            right,
            bottom) }
    }
}

internal fun OfficeViewModel.cropElementImage(
    el: OdfSlideElement,
    l: Float,
    t: Float,
    r: Float,
    b: Float): OdfSlideElement {
    val frameImage = (el as? OdfSlideElement.Frame)?.frame?.image
    return if (el is OdfSlideElement.Frame && frameImage != null)
        OdfSlideElement.Frame(el.frame.copy(image = frameImage.copy(
            cropLeftPct = l,
            cropTopPct = t,
            cropRightPct = r,
            cropBottomPct = b)))
    else el
}

/** Sets fill color on a slide shape/frame element. (extra) */
fun OfficeViewModel.setSlideElementFill(slideIndex: Int, elementIndex: Int, color: Long?) =
    setSlideElementColors(slideIndex, elementIndex, fill = color, setFill = true)

/** Sets stroke (border) color on a slide shape/frame element. (extra) */
fun OfficeViewModel.setSlideElementStroke(slideIndex: Int, elementIndex: Int, color: Long?) =
    setSlideElementColors(slideIndex, elementIndex, stroke = color, setStroke = true)

internal fun OfficeViewModel.setSlideElementColors(
    slideIndex: Int,
    elementIndex: Int,
    fill: Long? = null,
    stroke: Long? = null,
    setFill: Boolean = false,
    setStroke: Boolean = false) {
    val doc = (state.value as? OfficeViewModel.ViewState.Loaded)?.document as? OdfDocument.Presentation ?: return
    val slides = doc.slides.toMutableList()
    val slide = slides.getOrNull(slideIndex) ?: return
    val elements = slide.elements.toMutableList()
    val el = elements.getOrNull(elementIndex) ?: return
    elements[elementIndex] = recolorSlideElement(el, fill, stroke, setFill, setStroke)
    slides[slideIndex] = slide.copy(elements = elements)
    updateDocument(doc.copy(slides = slides))
}

/** Returns the frame element with fill/stroke colors updated per the set flags. */
internal fun frameColorUpdate(
    frame: OdfFrame,
    fill: Long?,
    stroke: Long?,
    setFill: Boolean,
    setStroke: Boolean,
): OdfSlideElement.Frame = OdfSlideElement.Frame(frame.copy(
    fillColor = if (setFill) fill else frame.fillColor,
    strokeColor = if (setStroke) stroke else frame.strokeColor
))

/** Returns the shape element with fill/stroke colors updated per the set flags. */
internal fun shapeColorUpdate(
    shape: OdfShape,
    fill: Long?,
    stroke: Long?,
    setFill: Boolean,
    setStroke: Boolean,
): OdfSlideElement.Shape {
    val nf = if (setFill) fill else shape.fillColor
    val ns = if (setStroke) stroke else shape.strokeColor
    return OdfSlideElement.Shape(when (shape) {
        is OdfShape.Rect -> shape.copy(fillColor = nf, strokeColor = ns)
        is OdfShape.Ellipse -> shape.copy(fillColor = nf, strokeColor = ns)
        is OdfShape.Line -> shape.copy(fillColor = nf, strokeColor = ns)
        is OdfShape.CustomShape -> shape.copy(fillColor = nf, strokeColor = ns)
        is OdfShape.Polyline -> shape.copy(fillColor = nf, strokeColor = ns)
    })
}

/** Returns the slide element with fill/stroke colors updated per the set flags. */
internal fun recolorSlideElement(
    el: OdfSlideElement,
    fill: Long?,
    stroke: Long?,
    setFill: Boolean,
    setStroke: Boolean,
): OdfSlideElement = when (el) {
    is OdfSlideElement.Frame -> frameColorUpdate(el.frame, fill, stroke, setFill, setStroke)
    is OdfSlideElement.Shape -> shapeColorUpdate(el.shape, fill, stroke, setFill, setStroke)
}
