package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableColumn
import com.vayunmathur.library.ui.odf.OdfTableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * W4: [TextDocumentView] tables — merged (covered) cells in both
 * directions, empty tables, styled cells, and real cell text edits
 * flowing out through `onCellTextChange`.
 */
@RunWith(AndroidJUnit4::class)
class W4TextTableViewTest {

    @get:Rule val rule = createComposeRule()

    private fun cell(text: String, bold: Boolean = false) = OdfTableCell(
        paragraphs = listOf(OdfParagraph(listOf(OdfSpan(text, bold = bold)))))

    private fun richDoc(): OdfDocument.TextDocument {
        val table = OdfTable(
            name = "T",
            columns = listOf(OdfTableColumn(90f), OdfTableColumn(null), OdfTableColumn(50f)),
            rows = listOf(
                OdfTableRow(listOf(
                    cell("merged head", bold = true).copy(colSpan = 2, backgroundColor = 0xFFEE00L),
                    cell("covered-by-merge").copy(isCovered = true),
                    cell("tail cell"),
                )),
                OdfTableRow(listOf(
                    cell("below merge"),
                    cell("editable cell"),
                    cell("rowspan host").copy(rowSpan = 2),
                )),
                OdfTableRow(listOf(
                    cell("third row"),
                    cell("last editable"),
                    cell("covered below").copy(isCovered = true),
                )),
            ),
            headerRowCount = 1,
        )
        return OdfDocument.TextDocument(
            "TableDoc",
            listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("before table")))),
                OdfContentBlock.Table(table),
                OdfContentBlock.Table(OdfTable(name = "Empty")),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("after table")))),
            ),
            headerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("doc header")))),
            footerParagraphs = listOf(OdfParagraph(listOf(OdfSpan("doc footer")))),
        )
    }

    @Test fun richTableRendersMergesAndEmptyTable() {
        rule.setContent { TextDocumentView(doc = richDoc()) }
        rule.onNodeWithText("doc header", substring = true).assertExists()
        rule.onNodeWithText("merged head", substring = true).assertExists()
        rule.onNodeWithText("below merge", substring = true).assertExists()
        rule.onNodeWithText("(empty table)", substring = true).assertExists()
    }

    @Test fun cellEditingReportsChangesAndFocus() {
        val changes = mutableListOf<List<Any?>>()
        var focused: List<Int>? = null
        rule.setContent {
            TextDocumentView(
                doc = richDoc(),
                onCellTextChange = { block, row, col, text -> changes.add(listOf(block, row, col, text)) },
                onCellFocus = { block, row, col -> focused = listOf(block, row, col) },
            )
        }
        // Editable cells are text fields in document order; edit the
        // "editable cell" (second field of row 2: block 1, row 1, col 1).
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().size >= 4, "cells should be editable")
        fields[3].performTextInput("edited!")
        rule.waitForIdle()
        assertTrue(changes.isNotEmpty(), "edit should report a change")
        assertTrue(changes.last()[3].toString().contains("edited!"), changes.toString())
        assertEquals(1, changes.last()[0])
        assertTrue(focused != null, "focus should be reported")
    }
}
