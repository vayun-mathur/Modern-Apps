package com.vayunmathur.office.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 4 pass 2: TextFormatControls table/more/style/align menus driven end to end. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2FormatMenusTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc.odt",
        listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("styled text")))),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("second para")))),
            OdfContentBlock.Table(
                OdfTable(
                    name = "T",
                    rows = listOf(
                        OdfTableRow(listOf(OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c00"))))), OdfTableCell())),
                        OdfTableRow(listOf(OdfTableCell(), OdfTableCell())),
                    ),
                )
            ),
        ),
    )

    private fun loaded(vm: OfficeViewModel) =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument

    private fun showControls(
        viewModel: OfficeViewModel,
        doc: OdfDocument.TextDocument,
        actions: BottomBarActions = BottomBarActions(),
    ) {
        rule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextFormatControls(
                    doc = doc,
                    target = FormatTarget.TextRun(0, 0, 0, 6),
                    viewModel = viewModel,
                    activeTableBlock = 2, activeTableRow = 0, activeTableCol = 0,
                    actions = actions,
                )
            }
        }
    }

    private fun menuItem(label: String) =
        rule.onAllNodesWithText(label, substring = true, useUnmergedTree = true).let {
            it[it.fetchSemanticsNodes().size - 1]
        }

    @Test fun tableMenuRowColumnAndCellOps() {
        val doc = textDoc()
        val viewModel = vm(doc)
        var insertTableCalls = 0
        showControls(viewModel, doc, BottomBarActions(onInsertTable = { insertTableCalls++ }))

        fun tableMenuItem(labelRes: Int) {
            rule.onNodeWithText(str(com.vayunmathur.office.R.string.table)).performScrollTo().performClick()
            menuItem(str(labelRes)).performClick()
            rule.waitForIdle()
        }
        fun table() = (loaded(viewModel).content[2] as OdfContentBlock.Table).table

        tableMenuItem(com.vayunmathur.office.R.string.insert_row_below)
        assertEquals(3, table().rows.size)
        tableMenuItem(com.vayunmathur.office.R.string.insert_column_right)
        assertEquals(3, table().rows[0].cells.size)
        tableMenuItem(com.vayunmathur.office.R.string.bold_cell)
        val cellSpan = table().rows[0].cells[0].paragraphs[0].spans[0]
        assertTrue(cellSpan.bold, "bold cell toggles the cell span")
        tableMenuItem(com.vayunmathur.office.R.string.delete_column)
        assertEquals(2, table().rows[0].cells.size)
        tableMenuItem(com.vayunmathur.office.R.string.delete_row)
        assertEquals(2, table().rows.size)
        tableMenuItem(com.vayunmathur.office.R.string.insert_table)
        assertEquals(1, insertTableCalls)
    }

    @Test fun tableMenuMergeUnmergeItemsRun() {
        val doc = textDoc()
        val viewModel = vm(doc)
        showControls(viewModel, doc)
        fun tableMenuItem(labelRes: Int) {
            rule.onNodeWithText(str(com.vayunmathur.office.R.string.table)).performScrollTo().performClick()
            menuItem(str(labelRes)).performClick()
            rule.waitForIdle()
        }
        tableMenuItem(com.vayunmathur.office.R.string.merge_with_right)
        val merged = (loaded(viewModel).content[2] as OdfContentBlock.Table).table
        assertTrue(merged.rows[0].cells[0].colSpan >= 2 || merged.rows[0].cells.size < 2,
            "merge right widens or consumes the neighbour cell")
        tableMenuItem(com.vayunmathur.office.R.string.unmerge_cell)
        tableMenuItem(com.vayunmathur.office.R.string.merge_with_below)
        tableMenuItem(com.vayunmathur.office.R.string.italic_cell)
        val cellSpan = (loaded(viewModel).content[2] as OdfContentBlock.Table)
            .table.rows[0].cells[0].paragraphs[0].spans[0]
        assertTrue(cellSpan.italic)
    }

    @Test fun styleAndAlignMenusApplyEveryChoice() {
        val doc = textDoc()
        val viewModel = vm(doc)
        showControls(viewModel, doc)
        fun para() = (loaded(viewModel).content[0] as OdfContentBlock.Paragraph).paragraph

        for ((labelRes, style) in listOf(
            com.vayunmathur.office.R.string.heading_2 to ParagraphStyle.HEADING2,
            com.vayunmathur.office.R.string.heading_3 to ParagraphStyle.HEADING3,
            com.vayunmathur.office.R.string.heading_4 to ParagraphStyle.HEADING4,
            com.vayunmathur.office.R.string.normal to ParagraphStyle.BODY,
        )) {
            rule.onAllNodesWithText("Normal")[0].performClick()
            menuItem(str(labelRes)).performClick()
            assertEquals(style, para().style)
        }

        for ((labelRes, align) in listOf(
            com.vayunmathur.office.R.string.center to androidx.compose.ui.text.style.TextAlign.Center,
            com.vayunmathur.office.R.string.right to androidx.compose.ui.text.style.TextAlign.End,
            com.vayunmathur.office.R.string.justify to androidx.compose.ui.text.style.TextAlign.Justify,
            com.vayunmathur.office.R.string.left to androidx.compose.ui.text.style.TextAlign.Start,
        )) {
            rule.onNodeWithContentDescription("Align left", substring = true).performClick()
            menuItem(str(labelRes)).performClick()
            assertEquals(align, para().alignment)
        }
    }

    @Test fun listAndIndentButtonsToggle() {
        val doc = textDoc()
        val viewModel = vm(doc)
        showControls(viewModel, doc)
        fun para() = (loaded(viewModel).content[0] as OdfContentBlock.Paragraph).paragraph

        rule.onNodeWithContentDescription("Bulleted list", substring = true).performClick()
        assertEquals(ParagraphStyle.LIST_ITEM, para().style)
        rule.onNodeWithContentDescription("Numbered list", substring = true).performClick()
        assertEquals(com.vayunmathur.library.ui.odf.ListType.NUMBERED, para().listType)
        rule.onNodeWithContentDescription("Checkbox", substring = true).performClick()
        assertEquals(com.vayunmathur.library.ui.odf.ListType.CHECKBOX, para().listType)
    }

    @Test fun moreMenuParagraphOps() {
        val doc = textDoc()
        val viewModel = vm(doc)
        var fontSizeCalls = 0
        showControls(viewModel, doc, BottomBarActions(onFontSize = { fontSizeCalls++ }))
        fun moreItem(labelRes: Int) {
            rule.onNodeWithContentDescription("More").performScrollTo().performClick()
            menuItem(str(labelRes)).performClick()
            rule.waitForIdle()
        }
        moreItem(com.vayunmathur.office.R.string.font_size_2)
        assertEquals(1, fontSizeCalls)
        val before = loaded(viewModel).content.size
        moreItem(com.vayunmathur.office.R.string.duplicate_paragraph)
        assertEquals(before + 1, loaded(viewModel).content.size)
        moreItem(com.vayunmathur.office.R.string.clear_formatting)
        moreItem(com.vayunmathur.office.R.string.delete_paragraph)
        assertEquals(before, loaded(viewModel).content.size)
    }
}
