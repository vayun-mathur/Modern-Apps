package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4: spreadsheet editing ops on [OfficeViewModel] — cells, rows/columns,
 * sheets, formats, merge/sort/fill, comments and sizing.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelSheetSlice3Test : OfficeViewModelSlice3Harness() {

    private fun sheetDoc(
        rows: List<List<String>> = listOf(listOf("a1", "b1"), listOf("a2", "b2")),
        name: String = "Sheet 1",
    ) = OdfDocument.Spreadsheet(
        title = "Book",
        sheets = listOf(OdfSheet(name, rows.map { r -> OdfRow(r.map { OdfCell(text = it) }) })),
    )

    private fun loaded(): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(sheetDoc())
        return vm
    }

    private fun cells(vm: OfficeViewModel, sheet: Int = 0): List<OdfRow> =
        ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[sheet].rows

    @Test
    fun `updateCellText stores plain text and numbers`() = runTest {
        val vm = loaded()
        vm.updateCellText(0, 0, 0, "hello")
        var cell = cells(vm)[0].cells[0]
        assertEquals("hello", cell.text)
        assertEquals("string", cell.valueType)
        assertNull(cell.formula)
        vm.updateCellText(0, 0, 0, "3.5")
        cell = cells(vm)[0].cells[0]
        assertEquals(3.5, cell.numberValue)
        assertEquals("float", cell.valueType)
        assertTrue(vm.hasUnsavedChanges.value)
    }

    @Test
    fun `updateCellText apostrophe forces literal text`() = runTest {
        val vm = loaded()
        vm.updateCellText(0, 0, 0, "'=A1")
        val cell = cells(vm)[0].cells[0]
        assertEquals("=A1", cell.text)
        assertNull(cell.formula)
        vm.updateCellText(0, 0, 1, "'")
        assertEquals("", cells(vm)[0].cells[1].text)
    }

    @Test
    fun `updateCellText formula stores OpenFormula`() = runTest {
        val vm = loaded()
        vm.updateCellText(0, 0, 0, "=A2+B1")
        val cell = cells(vm)[0].cells[0]
        assertEquals("=A2+B1", cell.text)
        assertTrue(cell.formula!!.startsWith("of:="))
        assertEquals("float", cell.valueType)
        assertNull(cell.numberValue)
        // Text-producing function gets string type.
        vm.updateCellText(0, 1, 0, "=UPPER(A1)")
        assertEquals("string", cells(vm)[1].cells[0].valueType)
    }

    @Test
    fun `updateCellText out of bounds is a no-op`() = runTest {
        val vm = loaded()
        vm.updateCellText(0, 0, 9, "x")
        vm.updateCellText(0, 9, 0, "x")
        vm.updateCellText(9, 0, 0, "x")
        assertEquals("a1", cells(vm)[0].cells[0].text)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun `add delete rows and columns`() = runTest {
        val vm = loaded()
        vm.addRow(0, 0)
        assertEquals(3, cells(vm).size)
        assertEquals(2, cells(vm)[1].cells.size)
        vm.addColumn(0)
        assertEquals(3, cells(vm)[0].cells.size)
        vm.deleteColumn(0, 0)
        assertEquals(2, cells(vm)[0].cells.size)
        vm.deleteRow(0, 0)
        assertEquals(2, cells(vm).size)
        // Last row is protected.
        val single = newVm()
        single.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            sheetDoc(rows = listOf(listOf("only"))))
        single.deleteRow(0, 0)
        assertEquals(1, (((single.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows.size))
        single.deleteColumn(0, 0)
        assertEquals(1, (((single.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows[0].cells.size))
    }

    @Test
    fun `rename add delete sheet`() = runTest {
        val vm = loaded()
        vm.renameSheet(0, "Data")
        vm.addSheet()
        var doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet
        assertEquals("Data", doc.sheets[0].name)
        assertEquals(2, doc.sheets.size)
        assertEquals("Sheet 2", doc.sheets[1].name)
        assertEquals(10, doc.sheets[1].rows.size)
        vm.deleteSheet(1)
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet
        assertEquals(1, doc.sheets.size)
        // Sole sheet is protected.
        vm.deleteSheet(0)
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet
        assertEquals(1, doc.sheets.size)
        vm.deleteSheet(9)
        assertEquals(1, ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets.size)
    }

    @Test
    fun `cell formatting toggles`() = runTest {
        val vm = loaded()
        vm.setCellBold(0, 0, 0)
        vm.setCellItalic(0, 0, 0)
        vm.setCellColor(0, 0, 0, 0xFFFF0000L)
        vm.setCellBgColor(0, 0, 0, 0xFF00FF00L)
        vm.setCellAlignment(0, 0, 0, androidx.compose.ui.text.style.TextAlign.Right)
        vm.setCellBorder(0, 0, 0, 0xFF0000FFL)
        val cell = cells(vm)[0].cells[0]
        assertTrue(cell.bold)
        assertTrue(cell.italic)
        assertEquals(0xFFFF0000L, cell.textColor)
        assertEquals(0xFF00FF00L, cell.backgroundColor)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, cell.alignment)
        assertEquals(0xFF0000FFL, cell.borderColor)
        vm.setCellBold(0, 0, 0)
        assertFalse(cells(vm)[0].cells[0].bold)
    }

    @Test
    fun `cell number format maps to value type`() = runTest {
        val vm = loaded()
        vm.setCellNumberFormat(0, 0, 0, OdfNumberFormat(isDate = true))
        assertEquals("date", cells(vm)[0].cells[0].valueType)
        vm.setCellNumberFormat(0, 0, 0, OdfNumberFormat(percent = true))
        assertEquals("percentage", cells(vm)[0].cells[0].valueType)
        vm.setCellNumberFormat(0, 0, 0, OdfNumberFormat(currencySymbol = "$"))
        assertEquals("currency", cells(vm)[0].cells[0].valueType)
        vm.setCellNumberFormat(0, 0, 0, OdfNumberFormat(decimals = 2))
        assertEquals("float", cells(vm)[0].cells[0].valueType)
        val before = cells(vm)[0].cells[0].valueType
        vm.setCellNumberFormat(0, 0, 0, null)
        assertEquals(before, cells(vm)[0].cells[0].valueType)
    }

    @Test
    fun `freeze panes clamps negatives`() = runTest {
        val vm = loaded()
        vm.setSheetFreeze(0, 1, 2)
        var sheet = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0]
        assertEquals(1, sheet.freezeRows)
        assertEquals(2, sheet.freezeCols)
        vm.setSheetFreeze(0, -3, -1)
        sheet = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0]
        assertEquals(0, sheet.freezeRows)
        assertEquals(0, sheet.freezeCols)
    }

    @Test
    fun `fillDown copies values and shifts formulas`() = runTest {
        val vm = loaded()
        vm.updateCellText(0, 0, 0, "v")
        vm.fillDown(0, 0, 0, 1)
        assertEquals("v", cells(vm)[1].cells[0].text)
        // Formula fill shifts the relative reference down one row.
        val vm2 = loaded()
        vm2.updateCellText(0, 0, 1, "=A1")
        vm2.fillDown(0, 0, 1, 1)
        assertEquals("=A2", cells(vm2)[1].cells[1].text)
        // fillDownToEnd reaches the last row.
        vm2.fillDownToEnd(0, 0, 0)
        assertEquals("a1", cells(vm2)[1].cells[0].text)
        // Backward range is a no-op.
        vm2.fillDown(0, 1, 0, 0)
    }

    @Test
    fun `merge and unmerge cells`() = runTest {
        val vm = loaded()
        vm.mergeCells(0, 0, 0, 1, 1)
        var topLeft = cells(vm)[0].cells[0]
        assertEquals(2, topLeft.spannedColumns)
        assertEquals(2, topLeft.rowSpan)
        assertTrue(cells(vm)[1].cells[1].isCovered)
        vm.unmergeCells(0, 0, 0)
        topLeft = cells(vm)[0].cells[0]
        assertEquals(1, topLeft.spannedColumns)
        assertFalse(cells(vm)[1].cells[1].isCovered)
        // Unmerging a plain cell is a no-op.
        vm.unmergeCells(0, 0, 0)
        assertFalse(cells(vm)[0].cells[0].isCovered)
    }

    @Test
    fun `sortRows orders numerically then lexically`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            sheetDoc(rows = listOf(listOf("b", "2"), listOf("a", "10"), listOf("c", "1"))))
        vm.sortRows(0, 1, ascending = true)
        assertEquals(listOf("1", "2", "10"), cells(vm).map { it.cells[1].text })
        vm.sortRows(0, 0, ascending = false)
        assertEquals(listOf("c", "b", "a"), cells(vm).map { it.cells[0].text })
    }

    @Test
    fun `cell comments set and clear`() = runTest {
        val vm = loaded()
        assertEquals("", vm.cellCommentText(0, 0, 0))
        vm.setCellComment(0, 0, 0, "Ann", "review this")
        assertEquals("review this", vm.cellCommentText(0, 0, 0))
        vm.setCellComment(0, 0, 0, "Ann", "")
        assertEquals("", vm.cellCommentText(0, 0, 0))
        assertEquals("", vm.cellCommentText(9, 0, 0))
    }

    @Test
    fun `column widths and row heights grow lists`() = runTest {
        val vm = loaded()
        vm.setColumnWidth(0, 3, 120f)
        vm.setRowHeight(0, 4, 30f)
        val sheet = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0]
        assertEquals(120f, sheet.columnWidths[3])
        assertEquals(30f, sheet.rowHeights[4])
        vm.setColumnWidth(0, 0, null)
        assertNull(((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].columnWidths[0])
    }

    @Test
    fun `floating shapes images charts elements`() = runTest {
        val vm = loaded()
        vm.addShapeToSheet(0, "ellipse")
        vm.insertChartIntoSheet(0, com.vayunmathur.library.ui.odf.OdfChart(
            type = com.vayunmathur.library.ui.odf.ChartType.PIE,
            categories = listOf("a"),
            series = listOf(com.vayunmathur.library.ui.odf.OdfChartSeries("s", listOf(1f))),
        ))
        vm.insertImageIntoSheet(0, "f.png", byteArrayOf(7))
        var floating = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].floating
        assertEquals(3, floating.size)
        vm.updateSheetElementText(0, 0, "shape text")
        vm.setSheetElementBounds(0, 0, 1f, 2f, 50f, 60f)
        vm.rotateSheetImage(0, 2, 45f)
        val img = ((floating[2] as com.vayunmathur.library.ui.odf.OdfSlideElement.Frame).frame.image)!!
        // rotateSheetImage ran before the re-read; verify via fresh state below.
        assertEquals(0f, img.rotationDegrees)
        floating = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].floating
        val rotated = ((floating[2] as com.vayunmathur.library.ui.odf.OdfSlideElement.Frame).frame.image)!!
        assertEquals(45f, rotated.rotationDegrees)
        vm.setSheetImageCrop(0, 2, 0.1f, 0f, 0f, 0f)
        vm.replaceSheetImage(0, 2, "g.png", byteArrayOf(8))
        vm.deleteSheetElement(0, 0)
        assertEquals(2, ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].floating.size)
    }

    @Test
    fun `createNewSpreadsheet builds default grid`() = runTest {
        val vm = idleVm()
        vm.createNewSpreadsheet()
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet
        assertEquals("Untitled Spreadsheet", doc.title)
        assertEquals(10, doc.sheets[0].rows.size)
        assertEquals(5, doc.sheets[0].rows[0].cells.size)
        assertTrue(vm.isEditMode.value)
        assertTrue(vm.hasUnsavedChanges.value)
    }
}
