package com.vayunmathur.office.platform

import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W4: [OfficeViewModel.setSlideElementTextOn] rebuilds an element's text
 * for frames and every shape kind: multiline input becomes one paragraph
 * per line, the first span's formatting and the paragraph style carry
 * over, and empty elements get a blank template span.
 */
class W4SlidesElementTextTest : OfficeViewModelSlice3Harness() {

    private fun styledPara(text: String) = OdfParagraph(
        listOf(OdfSpan(text, bold = true, fontSize = 18f)),
        style = ParagraphStyle.HEADING2,
    )

    private fun texts(el: OdfSlideElement): List<String> = when (el) {
        is OdfSlideElement.Frame -> el.frame.paragraphs.map { it.spans.joinToString("") { s -> s.text } }
        is OdfSlideElement.Shape -> el.shape.text.map { it.spans.joinToString("") { s -> s.text } }
    }

    private fun firstSpan(el: OdfSlideElement): OdfSpan? = when (el) {
        is OdfSlideElement.Frame -> el.frame.paragraphs.firstOrNull()?.spans?.firstOrNull()
        is OdfSlideElement.Shape -> el.shape.text.firstOrNull()?.spans?.firstOrNull()
    }

    @Test
    fun `frame text is rebuilt line by line keeping style`() {
        val vm = newVm()
        val el = OdfSlideElement.Frame(
            OdfFrame(0f, 0f, 100f, 50f, listOf(styledPara("old"))))
        val out = vm.setSlideElementTextOn(el, "alpha\nbeta\n\ngamma")
        assertEquals(listOf("alpha", "beta", "", "gamma"), texts(out))
        assertEquals(true, firstSpan(out)?.bold)
        assertEquals(18f, firstSpan(out)?.fontSize)
        assertEquals(
            ParagraphStyle.HEADING2,
            (out as OdfSlideElement.Frame).frame.paragraphs.first().style)
    }

    @Test
    fun `every shape kind takes the new text`() {
        val vm = newVm()
        val shapes: List<OdfShape> = listOf(
            OdfShape.Rect(0f, 0f, 10f, 10f, text = listOf(styledPara("r"))),
            OdfShape.Ellipse(0f, 0f, 10f, 10f, text = listOf(styledPara("e"))),
            OdfShape.Line(0f, 0f, 10f, 10f, text = listOf(styledPara("l"))),
            OdfShape.CustomShape(0f, 0f, 10f, 10f, text = listOf(styledPara("c"))),
            OdfShape.Polyline(0f, 0f, 10f, 10f, text = listOf(styledPara("p"))),
        )
        for (shape in shapes) {
            val out = vm.setSlideElementTextOn(OdfSlideElement.Shape(shape), "one\ntwo")
            assertEquals(listOf("one", "two"), texts(out), shape.javaClass.simpleName)
            assertTrue(out is OdfSlideElement.Shape)
            assertEquals(shape.javaClass, (out as OdfSlideElement.Shape).shape.javaClass)
        }
    }

    @Test
    fun `empty element and empty text produce blank paragraphs`() {
        val vm = newVm()
        val frame = OdfSlideElement.Frame(OdfFrame(0f, 0f, 10f, 10f, emptyList()))
        val out = vm.setSlideElementTextOn(frame, "")
        assertEquals(listOf(""), texts(out))
        val rect = OdfSlideElement.Shape(OdfShape.Rect(0f, 0f, 5f, 5f))
        assertEquals(listOf("x"), texts(vm.setSlideElementTextOn(rect, "x")))
    }
}
