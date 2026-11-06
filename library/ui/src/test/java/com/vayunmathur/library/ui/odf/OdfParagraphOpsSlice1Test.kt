package com.vayunmathur.library.ui.odf

import androidx.compose.ui.text.style.TextAlign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Paragraph operations: list toggles, indent, style, moves, smart enter/backspace.
 * Slice-1 coverage for library/ui (odf package).
 */
class OdfParagraphOpsSlice1Test {

    private fun para(
        text: String = "hello",
        style: ParagraphStyle = ParagraphStyle.BODY,
        level: Int = 0,
        type: ListType = ListType.BULLET,
        index: Int = 0,
    ) = OdfContentBlock.Paragraph(
        OdfParagraph(listOf(OdfSpan(text)), style = style, listLevel = level, listType = type, listItemIndex = index)
    )

    private fun docOf(vararg blocks: OdfContentBlock) =
        OdfDocument.TextDocument(title = "t", content = blocks.toList())

    private fun styleOf(d: OdfDocument.TextDocument, i: Int) =
        (d.content[i] as OdfContentBlock.Paragraph).paragraph.style

    @Test
    fun `toggling a bullet makes and unmakes a list item`() {
        val on = docOf(para()).toggleListItem(0)!!
        assertEquals(ParagraphStyle.LIST_ITEM, styleOf(on, 0))
        val off = on.toggleListItem(0)!!
        assertEquals(ParagraphStyle.BODY, styleOf(off, 0))
    }

    @Test
    fun `toggling numbered renumbers the run`() {
        val d = docOf(
            para("a", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 1),
            para("b", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 1),
        )
        val out = d.toggleNumberedList(1)!!
        // Second item left the list: the first keeps index 1.
        assertEquals(ParagraphStyle.BODY, styleOf(out, 1))
        assertEquals(1, (out.content[0] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
    }

    @Test
    fun `list level changes clamp to 1-9`() {
        val d = docOf(para("a", ParagraphStyle.LIST_ITEM, 1))
        assertEquals(9, ((d.changeListLevel(0, 99)!!.content[0] as OdfContentBlock.Paragraph).paragraph.listLevel))
        assertEquals(1, ((d.changeListLevel(0, -99)!!.content[0] as OdfContentBlock.Paragraph).paragraph.listLevel))
        assertNull(docOf(para()).changeListLevel(0, 1))
        assertNull(docOf(OdfContentBlock.PageBreak).changeListLevel(0, 1))
    }

    @Test
    fun `restart numbering resets the run`() {
        val d = docOf(
            para("a", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 7),
            para("b", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 8),
        )
        val out = d.restartNumbering(0)!!
        assertEquals(1, (out.content[0] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
        assertEquals(2, (out.content[1] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
    }

    @Test
    fun `indent and outdent move the margin`() {
        val d = docOf(para())
        val indented = d.indentParagraph(0)!!
        assertEquals(24f, (indented.content[0] as OdfContentBlock.Paragraph).paragraph.marginLeft)
        val outdented = indented.outdentParagraph(0)!!
        assertEquals(0f, (outdented.content[0] as OdfContentBlock.Paragraph).paragraph.marginLeft)
        assertNull(docOf(OdfContentBlock.PageBreak).indentParagraph(0))
    }

    @Test
    fun `style and alignment set`() {
        val d = docOf(para())
        assertEquals(
            ParagraphStyle.HEADING1,
            styleOf(d.setParagraphStyle(0, ParagraphStyle.HEADING1)!!, 0),
        )
        val aligned = d.setParagraphAlignment(0, TextAlign.Center)!!
        assertEquals(TextAlign.Center, (aligned.content[0] as OdfContentBlock.Paragraph).paragraph.alignment)
        assertNull(docOf(OdfContentBlock.PageBreak).setParagraphStyle(0, ParagraphStyle.HEADING1))
    }

    @Test
    fun `span format maps every span`() {
        val out = docOf(para()).toggleSpanFormat(0) { it.copy(bold = !it.bold) }!!
        assertTrue((out.content[0] as OdfContentBlock.Paragraph).paragraph.spans.all { it.bold })
        assertNull(docOf(OdfContentBlock.PageBreak).toggleSpanFormat(0) { it })
    }

    @Test
    fun `add duplicate delete move paragraphs`() {
        val d = docOf(para("a"), para("b"))
        assertEquals(3, d.addParagraphAfter(0).content.size)
        // Duplicating inserts the copy right after the source.
        val duped = d.duplicateParagraph(0)!!
        assertEquals(3, duped.content.size)
        assertEquals("a", (((duped.content[1] as OdfContentBlock.Paragraph).paragraph.spans.first().text)))
        assertEquals(1, d.deleteParagraph(0)!!.content.size)
        assertNull(docOf(para("only")).deleteParagraph(0))
        assertNull(d.deleteParagraph(9))
        // Move b above a.
        val moved = d.moveParagraphUp(1)!!
        assertEquals("b", ((moved.content[0] as OdfContentBlock.Paragraph).paragraph.spans.first().text))
        assertNull(d.moveParagraphUp(0))
        val movedDown = d.moveParagraphDown(0)!!
        assertEquals("b", ((movedDown.content[0] as OdfContentBlock.Paragraph).paragraph.spans.first().text))
        assertNull(d.moveParagraphDown(1))
    }

    @Test
    fun `a horizontal line inserts a ruled paragraph`() {
        val d = docOf(para("a"))
        val out = d.insertHorizontalLine(0)
        assertEquals(2, out.content.size)
        assertNotNull((out.content[1] as OdfContentBlock.Paragraph).paragraph.borderColor)
    }

    @Test
    fun `checkbox toggles make and check items`() {
        val made = docOf(para()).toggleCheckbox(0)!!
        val p = (made.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.LIST_ITEM, p.style)
        assertEquals(ListType.CHECKBOX, p.listType)
        assertFalse(p.listChecked)
        val checked = made.setCheckboxChecked(0, true)!!
        assertTrue((checked.content[0] as OdfContentBlock.Paragraph).paragraph.listChecked)
        // A non-checkbox paragraph rejects the checked setter.
        assertNull(docOf(para()).setCheckboxChecked(0, true))
    }

    @Test
    fun `enter on an empty list item exits the list`() {
        val d = docOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("")), style = ParagraphStyle.LIST_ITEM, listLevel = 1))
        )
        val (out, caret) = d.handleListEnter(0, 0, 0)!!
        assertEquals(ParagraphStyle.BODY, styleOf(out, 0))
        assertEquals(0, caret)
    }

    @Test
    fun `enter splits a paragraph at the caret`() {
        val d = docOf(para("hello"))
        val (out, caret) = d.handleListEnter(0, 0, 2)!!
        assertEquals(2, out.content.size)
        assertEquals("he", ((out.content[0] as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { it.text }))
        assertEquals("llo", ((out.content[1] as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { it.text }))
        assertEquals(3, caret)
        assertNull(d.handleListEnter(0, 9, 0))
    }

    @Test
    fun `backspace at a list start outdents or unlists`() {
        val nested = docOf(para("a", ParagraphStyle.LIST_ITEM, 2))
        val (out, _) = nested.handleListBackspace(0, 0, 0)!!
        assertEquals(1, ((out.content[0] as OdfContentBlock.Paragraph).paragraph.listLevel))
        val top = docOf(para("a", ParagraphStyle.LIST_ITEM, 1))
        val (plain, _) = top.handleListBackspace(0, 0, 0)!!
        assertEquals(ParagraphStyle.BODY, styleOf(plain, 0))
        // Mid-paragraph backspace is the default delete (null).
        assertNull(top.handleListBackspace(0, 0, 2))
        assertNull(docOf(para()).handleListBackspace(0, 0, 0))
    }

    @Test
    fun `renumbering assigns per-level indices`() {
        val d = docOf(
            para("a", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 0),
            para("b", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 0),
        )
        val out = renumberLists(d)
        assertEquals(1, (out.content[0] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
        assertEquals(2, (out.content[1] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
    }

    @Test
    fun `unnumbered imports gain numbers, numbered ones keep theirs`() {
        val d = docOf(
            para("a", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 0),
            para("b", ParagraphStyle.LIST_ITEM, 1, ListType.NUMBERED, 5),
        )
        val out = numberUnnumberedListItems(d)
        assertEquals(1, (out.content[0] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
        assertEquals(5, (out.content[1] as OdfContentBlock.Paragraph).paragraph.listItemIndex)
    }
}
