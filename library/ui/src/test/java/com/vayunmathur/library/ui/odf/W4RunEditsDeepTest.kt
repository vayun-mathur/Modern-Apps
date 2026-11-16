package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W4: deep run-edit branches — multi-paragraph replacement with new
 * paragraphs in the middle, whole-run deletion, run styling that touches
 * only parts of the edge paragraphs, list Enter/Backspace behaviour at
 * several caret positions, and links inside a run.
 */
class W4RunEditsDeepTest {

    private fun docOf(vararg paras: OdfParagraph): OdfDocument.TextDocument =
        OdfDocument.TextDocument(
            title = "T",
            content = paras.map { OdfContentBlock.Paragraph(it) },
        )

    private fun texts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .map { it.paragraph.spans.joinToString("") { s -> s.text } }

    private fun para(text: String, style: ParagraphStyle = ParagraphStyle.BODY) =
        OdfParagraph(listOf(OdfSpan(text)), style = style)

    // ---- updateParagraphRun ----

    @Test
    fun `run replacement inserts middle paragraphs and keeps tail`() {
        val doc = docOf(para("alpha"), para("bravo"), para("charlie"))
        // Replace "ha\nbravo\nchar" (crossing all three) with two new lines.
        val out = doc.updateParagraphRun(0, 2, "alpX\nYlie")
        assertNotNull(out)
        assertEquals(listOf("alpX", "Ylie"), texts(out))
    }

    @Test
    fun `run replacement with three segments`() {
        val doc = docOf(para("one"), para("two"))
        val out = doc.updateParagraphRun(0, 1, "one\nmiddle\ntwo")
        assertNotNull(out)
        assertEquals(listOf("one", "middle", "two"), texts(out))
    }

    @Test
    fun `deleting a whole run leaves one empty paragraph`() {
        val doc = docOf(para("gone"), para("also gone"))
        val out = doc.updateParagraphRun(0, 1, "")
        assertNotNull(out)
        assertEquals(listOf(""), texts(out))
    }

    @Test
    fun `run update preserves surrounding blocks outside the run`() {
        val doc = docOf(para("before"), para("mid one"), para("mid two"), para("after"))
        val out = doc.updateParagraphRun(1, 2, "merged")
        assertNotNull(out)
        assertEquals(listOf("before", "merged", "after"), texts(out))
    }

    @Test
    fun `insert text at run boundaries`() {
        val doc = docOf(para("ab"), para("cd"))
        assertEquals(listOf("Xab", "cd"), texts(assertNotNull(doc.insertTextInRun(0, 1, 0, "X"))))
        assertEquals(listOf("ab", "cdX"), texts(assertNotNull(doc.insertTextInRun(0, 1, 5, "X"))))
        assertEquals(listOf("ab", "cXd"), texts(assertNotNull(doc.insertTextInRun(0, 1, 4, "X"))))
        // Out-of-range positions clamp instead of failing.
        assertEquals(listOf("ab", "cdX"), texts(assertNotNull(doc.insertTextInRun(0, 1, 99, "X"))))
    }

    // ---- applyRunSpanStyle / runRangeHasFormat ----

    @Test
    fun `run style applies only to covered chars of edge paragraphs`() {
        val doc = docOf(para("aaaa"), para("bbbb"), para("cccc"))
        // Global text "aaaa\nbbbb\ncccc": bold chars 2..10 (tail of p0, all of p1, head of p2).
        val out = doc.applyRunSpanStyle(0, 2, 2, 11) { it.copy(bold = true) }
        assertNotNull(out)
        val paras = out.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        fun boldText(p: OdfParagraph) = p.spans.filter { it.bold }.joinToString("") { it.text }
        assertEquals("aa", boldText(paras[0]))
        assertEquals("bbbb", boldText(paras[1]))
        assertEquals("c", boldText(paras[2]))
        assertFalse(doc.runRangeHasFormat(0, 2, 0, 4) { it.bold })
        assertTrue(out.runRangeHasFormat(0, 2, 5, 9) { it.bold })
    }

    @Test
    fun `collapsed run style bolds whole paragraph at caret`() {
        val doc = docOf(para("word"), para("next"))
        val out = doc.applyRunSpanStyle(0, 1, 2, 2) { it.copy(italic = true) }
        assertNotNull(out)
        assertTrue(out.runRangeHasFormat(0, 1, 0, 4) { it.italic })
        assertFalse(out.runRangeHasFormat(0, 1, 5, 9) { it.italic })
    }

    @Test
    fun `mutate run paragraphs only transforms covered paragraphs`() {
        val doc = docOf(para("p0"), para("p1"), para("p2"))
        val out = doc.mutateRunParagraphs(0, 2, 3, 6) { it.copy(alignment = androidx.compose.ui.text.style.TextAlign.Center) }
        assertNotNull(out)
        val paras = out.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        assertEquals(null, paras[0].alignment)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, paras[1].alignment)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, paras[2].alignment)
        assertEquals(1, doc.runParagraphIndexAt(0, 2, 4))
    }

    // ---- handleListEnter / handleListBackspace ----

    private fun listPara(text: String, level: Int = 1, index: Int = 1) = OdfParagraph(
        listOf(OdfSpan(text)),
        style = ParagraphStyle.LIST_ITEM,
        listType = ListType.NUMBERED,
        listLevel = level,
        listItemIndex = index,
    )

    @Test
    fun `enter in middle of list item splits and renumbers`() {
        val doc = docOf(listPara("first item", index = 1), listPara("second", index = 2))
        val (out, caret) = assertNotNull(doc.handleListEnter(0, 1, 5))
        assertEquals(listOf("first", " item", "second"), texts(out))
        assertEquals(6, caret)
        val styles = out.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph.style }
        assertTrue(styles.all { it == ParagraphStyle.LIST_ITEM })
    }

    @Test
    fun `enter on empty list item exits the list`() {
        val doc = docOf(listPara("real", index = 1), listPara("", index = 2))
        val (out, _) = assertNotNull(doc.handleListEnter(0, 1, 5))
        val paras = out.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        assertEquals(ParagraphStyle.BODY, paras[1].style)
        assertEquals(0, paras[1].listLevel)
    }

    @Test
    fun `enter at end of heading creates body paragraph`() {
        val doc = docOf(para("Title", ParagraphStyle.HEADING1))
        val (out, _) = assertNotNull(doc.handleListEnter(0, 0, 5))
        assertEquals(listOf("Title", ""), texts(out))
        val paras = out.content.filterIsInstance<OdfContentBlock.Paragraph>().map { it.paragraph }
        assertEquals(ParagraphStyle.BODY, paras[1].style)
    }

    @Test
    fun `backspace at start outdents then exits list`() {
        val nested = docOf(listPara("deep", level = 2))
        val (outdented, _) = assertNotNull(nested.handleListBackspace(0, 0, 0))
        val p1 = (outdented.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(1, p1.listLevel)
        assertEquals(ParagraphStyle.LIST_ITEM, p1.style)

        val top = docOf(listPara("top", level = 1))
        val (exited, _) = assertNotNull(top.handleListBackspace(0, 0, 0))
        val p2 = (exited.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ParagraphStyle.BODY, p2.style)

        // Not at the start, or not a list item: no smart handling.
        assertNull(top.handleListBackspace(0, 0, 2))
        assertNull(docOf(para("plain")).handleListBackspace(0, 0, 0))
    }

    // ---- links in a run ----

    @Test
    fun `set link in run and find it back`() {
        val doc = docOf(para("click here now"), para("other"))
        val out = assertNotNull(doc.setLinkInRun(0, 1, 6, 10, "here", "https://example.org"))
        assertEquals(listOf("click here now", "other"), texts(out))
        val link = out.linkAt(0, 1, 8)
        assertNotNull(link)
        assertEquals("here", link.text)
        assertEquals("https://example.org", link.url)
        assertEquals(6, link.gStart)
        assertEquals(10, link.gEnd)
        // Caret on plain text finds no link; a link cannot span paragraphs.
        assertNull(out.linkAt(0, 1, 0))
        assertNull(doc.setLinkInRun(0, 1, 6, 16, "x", "https://example.org"))
    }

    @Test
    fun `checkbox toggles round trip`() {
        val doc = docOf(para("task"))
        val asBox = assertNotNull(doc.toggleCheckbox(0))
        val p = (asBox.content[0] as OdfContentBlock.Paragraph).paragraph
        assertEquals(ListType.CHECKBOX, p.listType)
        val checked = assertNotNull(asBox.setCheckboxChecked(0, true))
        assertTrue((checked.content[0] as OdfContentBlock.Paragraph).paragraph.listChecked)
        // Setting checked on a non-checkbox is a no-op.
        assertNull(doc.setCheckboxChecked(0, true))
        val back = assertNotNull(checked.toggleCheckbox(0))
        assertEquals(ParagraphStyle.BODY, (back.content[0] as OdfContentBlock.Paragraph).paragraph.style)
    }
}
