package com.vayunmathur.office.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 pure-logic tests: the math/formula/number-format helpers owned by
 * this slice (OdfMath, OmmlToMathml, ExcelNumFmt, ExcelFormula extras).
 */
class Slice4MathLogicTest {

    // ---- OdfMath.parse / toText ----

    @Test fun mathParsesSimpleRow() {
        val node = OdfMath.parse("<math><mi>x</mi><mo>+</mo><mn>2</mn></math>")
        assertNotNull(node)
        assertEquals("x+2", OdfMath.toText(node))
    }

    @Test fun mathParsesFraction() {
        val node = OdfMath.parse("<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>")
        assertNotNull(node)
        assertEquals("(a)/(b)", OdfMath.toText(node))
    }

    @Test fun mathParsesSupSub() {
        val sup = OdfMath.parse("<math><msup><mi>x</mi><mn>2</mn></msup></math>")
        assertEquals("x^2", OdfMath.toText(assertNotNull(sup)))
        val sub = OdfMath.parse("<math><msub><mi>x</mi><mi>i</mi></msub></math>")
        assertEquals("x_i", OdfMath.toText(assertNotNull(sub)))
    }

    @Test fun mathParsesSubSup() {
        val node = OdfMath.parse("<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>")
        assertEquals("x_i^2", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathParsesSqrtAndRoot() {
        val sqrt = OdfMath.parse("<math><msqrt><mi>x</mi></msqrt></math>")
        assertEquals("√(x)", OdfMath.toText(assertNotNull(sqrt)))
        val root = OdfMath.parse("<math><mroot><mi>x</mi><mn>3</mn></mroot></math>")
        assertEquals("root[3](x)", OdfMath.toText(assertNotNull(root)))
    }

    @Test fun mathParsesFenced() {
        val node = OdfMath.parse("<math><mfenced open=\"[\" close=\"]\"><mi>x</mi></mfenced></math>")
        assertEquals("[x]", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathParsesUnderOver() {
        val under = OdfMath.parse("<math><munder><mi>x</mi><mo>~</mo></munder></math>")
        assertEquals("x_~", OdfMath.toText(assertNotNull(under)))
        val over = OdfMath.parse("<math><mover><mi>x</mi><mo>^</mo></mover></math>")
        assertEquals("x^^", OdfMath.toText(assertNotNull(over)))
        val both = OdfMath.parse("<math><munderover><mo>∑</mo><mi>i</mi><mi>n</mi></munderover></math>")
        assertEquals("∑_i^n", OdfMath.toText(assertNotNull(both)))
    }

    @Test fun mathParsesTable() {
        val node = OdfMath.parse(
            "<math><mtable><mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr></mtable></math>")
        // mtr/mtd fall back to Row/TableRow text: "a, b".
        assertEquals("a, b", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathParsesMultiscripts() {
        val node = OdfMath.parse(
            "<math><mmultiscripts><mi>x</mi><mi>i</mi><mn>2</mn></mmultiscripts></math>")
        assertEquals("x_i^2", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathSkipsAnnotations() {
        val node = OdfMath.parse(
            "<math><semantics><mi>x</mi><annotation>ignore me</annotation></semantics></math>")
        assertEquals("x", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathHandlesMspaceAndUnknownTags() {
        val node = OdfMath.parse("<math><mspace/><foo><mi>y</mi></foo></math>")
        assertEquals(" y", OdfMath.toText(assertNotNull(node)))
    }

    @Test fun mathReturnsNullWithoutMathTag() {
        assertNull(OdfMath.parse("<doc><mi>x</mi></doc>"))
    }

    @Test fun mathReturnsNullOnGarbage() {
        // Empty / non-XML input has no <math> tag -> null.
        assertNull(OdfMath.parse(""))
        assertNull(OdfMath.parse("just text"))
    }

    @Test fun mathToTextCoversNodeKinds() {
        assertIs<MathNode.Row>(OdfMath.parse("<math><mrow><mi>a</mi></mrow></math>"))
        // parse() always wraps in a top-level Row; the table is its first child.
        val table = OdfMath.parse("<math><mtable><mtr><mtd><mi>a</mi></mtd></mtr></mtable></math>")
        val row = assertNotNull(table) as MathNode.Row
        assertIs<MathNode.Table>(row.children[0])
        val fencedDefault = OdfMath.parse("<math><mfenced><mi>q</mi></mfenced></math>")
        assertEquals("(q)", OdfMath.toText(assertNotNull(fencedDefault)))
    }

    // ---- OmmlToMathml.convert ----

    @Test fun ommlConvertsRun() {
        val out = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:r><m:t>x+2</m:t></m:r></m:oMath>")
        assertNotNull(out)
        assertTrue(out.contains("<mi>x</mi>"), out)
        assertTrue(out.contains("<mo>+</mo>"), out)
        assertTrue(out.contains("<mn>2</mn>"), out)
    }

    @Test fun ommlReturnsNullWhenNoOMath() {
        assertNull(OmmlToMathml.convert("<w:p xmlns:w=\"x\"><w:r><w:t>hi</w:t></w:r></w:p>"))
    }

    @Test fun ommlConvertsFraction() {
        val out = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:f><m:num><m:r><m:t>a</m:t></m:r></m:num>" +
                "<m:den><m:r><m:t>b</m:t></m:r></m:den></m:f></m:oMath>")
        assertTrue(assertNotNull(out).contains("<mfrac>"), out)
    }

    @Test fun ommlConvertsSupSubAndSubSup() {
        val sup = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:sSup><m:e><m:r><m:t>x</m:t></m:r></m:e>" +
                "<m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSup></m:oMath>")
        assertTrue(assertNotNull(sup).contains("<msup>"), sup)
        val sub = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:sSub><m:e><m:r><m:t>x</m:t></m:r></m:e>" +
                "<m:sub><m:r><m:t>i</m:t></m:r></m:sub></m:sSub></m:oMath>")
        assertTrue(assertNotNull(sub).contains("<msub>"), sub)
        val both = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:sSubSup><m:e><m:r><m:t>x</m:t></m:r></m:e>" +
                "<m:sub><m:r><m:t>i</m:t></m:r></m:sub>" +
                "<m:sup><m:r><m:t>2</m:t></m:r></m:sup></m:sSubSup></m:oMath>")
        assertTrue(assertNotNull(both).contains("<msubsup>"), both)
    }

    @Test fun ommlConvertsRadWithAndWithoutDegree() {
        val sqrt = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:rad><m:e><m:r><m:t>x</m:t></m:r></m:e></m:rad></m:oMath>")
        assertTrue(assertNotNull(sqrt).contains("<msqrt>"), sqrt)
        val root = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:rad><m:deg><m:r><m:t>3</m:t></m:r></m:deg>" +
                "<m:e><m:r><m:t>x</m:t></m:r></m:e></m:rad></m:oMath>")
        assertTrue(assertNotNull(root).contains("<mroot>"), root)
    }

    @Test fun ommlConvertsNaryDelimFuncMatrix() {
        val nary = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:nary><m:naryPr><m:chr m:val=\"∑\"/></m:naryPr>" +
                "<m:sub><m:r><m:t>i</m:t></m:r></m:sub>" +
                "<m:e><m:r><m:t>x</m:t></m:r></m:e></m:nary></m:oMath>")
        val naryOut = assertNotNull(nary)
        assertTrue(naryOut.contains("<munder>"), naryOut)
        val naryBoth = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:nary><m:sub><m:r><m:t>a</m:t></m:r></m:sub>" +
                "<m:sup><m:r><m:t>b</m:t></m:r></m:sup>" +
                "<m:e><m:r><m:t>c</m:t></m:r></m:e></m:nary></m:oMath>")
        assertTrue(assertNotNull(naryBoth).contains("<munderover>"))
        val narySupOnly = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:nary><m:sup><m:r><m:t>b</m:t></m:r></m:sup>" +
                "<m:e><m:r><m:t>c</m:t></m:r></m:e></m:nary></m:oMath>")
        assertTrue(assertNotNull(narySupOnly).contains("<mover>"))
        val delim = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:d><m:dPr><m:begChr m:val=\"[\"/>" +
                "<m:endChr m:val=\"]\"/></m:dPr>" +
                "<m:e><m:r><m:t>x</m:t></m:r></m:e></m:d></m:oMath>")
        assertTrue(assertNotNull(delim).contains("<mo>[</mo>"), delim)
        val func = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:func><m:fName><m:r><m:t>sin</m:t></m:r></m:fName>" +
                "<m:e><m:r><m:t>x</m:t></m:r></m:e></m:func></m:oMath>")
        assertTrue(assertNotNull(func).contains("sin"), func)
        val matrix = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:m><m:mr><m:e><m:r><m:t>a</m:t></m:r></m:e>" +
                "<m:e><m:r><m:t>b</m:t></m:r></m:e></m:mr></m:m></m:oMath>")
        assertTrue(assertNotNull(matrix).contains("<mtable>"), matrix)
    }

    @Test fun ommlClassifyTextNumbersAndOps() {
        val out = OmmlToMathml.convert(
            "<m:oMath xmlns:m=\"x\"><m:r><m:t>12.5 ab+% &lt;</m:t></m:r></m:oMath>")
        val s = assertNotNull(out)
        assertTrue(s.contains("<mn>12.5</mn>"), s)
        assertTrue(s.contains("<mi>ab</mi>"), s)
        assertTrue(s.contains("<mo>+</mo>"), s)
        assertTrue(s.contains("<mo>%</mo>"), s)
        // "&lt;" in the XML source decodes to '<', which the renderer re-escapes.
        assertTrue(s.contains("&lt;"), s)
    }

    // ---- ExcelNumFmt ----

    @Test fun numFmtBuiltinGeneralIsNull() {
        assertNull(ExcelNumFmt.forBuiltin(0))
        assertNull(ExcelNumFmt.parse("General"))
        assertNull(ExcelNumFmt.parse(""))
    }

    @Test fun numFmtBuiltinDecimals() {
        assertEquals(2, ExcelNumFmt.forBuiltin(2)?.decimals)
        assertEquals(0, ExcelNumFmt.forBuiltin(1)?.decimals)
    }

    @Test fun numFmtBuiltinGroupingAndPercent() {
        val grouped = ExcelNumFmt.forBuiltin(3)
        assertEquals(true, grouped?.grouping)
        val pct = ExcelNumFmt.forBuiltin(9)
        assertEquals(true, pct?.percent)
        val sci = ExcelNumFmt.forBuiltin(11)
        assertEquals(true, sci?.isScientific)
    }

    @Test fun numFmtBuiltinFraction() {
        assertEquals(true, ExcelNumFmt.forBuiltin(12)?.isFraction)
        assertEquals(2, ExcelNumFmt.forBuiltin(13)?.fractionDenominatorDigits)
    }

    @Test fun numFmtBuiltinDates() {
        assertTrue(ExcelNumFmt.isDateTimeBuiltin(14))
        assertTrue(ExcelNumFmt.isDateTimeBuiltin(45))
        assertTrue(ExcelNumFmt.isDateTimeBuiltin(27))
        assertTrue(ExcelNumFmt.isDateTimeBuiltin(50))
        assertTrue(!ExcelNumFmt.isDateTimeBuiltin(2))
        val d = ExcelNumFmt.forBuiltin(14)
        assertEquals(true, d?.isDate)
        // Locale/CJK builtins fall back to a default date pattern.
        assertEquals(true, ExcelNumFmt.forBuiltin(27)?.isDate)
        assertEquals(true, ExcelNumFmt.forBuiltin(50)?.isDate)
        assertNull(ExcelNumFmt.forBuiltin(999))
    }

    @Test fun numFmtCustomNumeric() {
        val f = ExcelNumFmt.parse("#,##0.00")
        assertEquals(2, f?.decimals)
        assertEquals(true, f?.grouping)
        val plain = ExcelNumFmt.parse("0")
        assertEquals(0, plain?.decimals)
    }

    @Test fun numFmtCustomDateTime() {
        val dt = ExcelNumFmt.parse("yyyy-mm-dd")
        assertEquals(true, dt?.isDate)
        assertTrue(dt?.dateTimeTokens?.any { it.kind == "year" } == true)
        assertTrue(dt?.dateTimeTokens?.any { it.kind == "month" } == true)
        assertTrue(dt?.dateTimeTokens?.any { it.kind == "day" } == true)
        val tm = ExcelNumFmt.parse("h:mm:ss")
        assertEquals(true, tm?.isTime)
        val ampm = ExcelNumFmt.parse("h:mm AM/PM")
        assertTrue(ampm?.dateTimeTokens?.any { it.kind == "am-pm" } == true)
    }

    @Test fun numFmtMinuteVsMonth() {
        // 'm' after an hour is minutes.
        val hm = ExcelNumFmt.parse("h:mm")
        val kinds = hm?.dateTimeTokens?.map { it.kind } ?: emptyList()
        assertTrue(kinds.contains("minutes"), kinds.toString())
        // 'mmm' is a textual month.
        val mmm = ExcelNumFmt.parse("d-mmm-yy")
        assertTrue(mmm?.dateTimeTokens?.any { it.kind == "month" && it.textual } == true)
    }

    @Test fun numFmtTextCodeIsNull() {
        assertNull(ExcelNumFmt.parse("@"))
    }

    @Test fun numFmtFirstSectionOnly() {
        val f = ExcelNumFmt.parse("0.00;[Red]-0.00")
        assertEquals(2, f?.decimals)
    }

    @Test fun numFmtCurrency() {
        val f = ExcelNumFmt.parse("\$#,##0.00")
        assertEquals("$", f?.currencySymbol)
        val euro = ExcelNumFmt.parse("[$€-407]#,##0.00")
        assertEquals("€", euro?.currencySymbol)
    }

    @Test fun numFmtQuotedLiteralNotDate() {
        // 0" days" is a number with a literal suffix, not a date.
        val f = ExcelNumFmt.parse("0\" days\"")
        assertEquals(false, f?.isDate)
        assertEquals(false, f?.isTime)
    }

    @Test fun numFmtDayOfWeekAndEscapes() {
        val dow = ExcelNumFmt.parse("ddd")
        assertTrue(dow?.dateTimeTokens?.any { it.kind == "day-of-week" } == true)
        val esc = ExcelNumFmt.parse("yyyy\\-mm\\-dd")
        assertTrue(esc?.dateTimeTokens?.any { it.kind == "text" } == true)
        val ap = ExcelNumFmt.parse("h:mm A/P")
        assertTrue(ap?.dateTimeTokens?.any { it.kind == "am-pm" } == true)
    }

    // ---- ExcelFormula.shift ----

    @Test fun formulaShiftMovesRelativeRefs() {
        assertEquals("B2", ExcelFormula.shift("A1", 1, 1))
        // Absolute parts stay; relative parts move ($A1 -> col fixed, row 1+5).
        assertEquals("\$A6", ExcelFormula.shift("\$A1", 5, 5))
        assertEquals("F\$1", ExcelFormula.shift("A\$1", 5, 5))
        assertEquals("A1", ExcelFormula.shift("A1", 0, 0))
    }

    @Test fun formulaShiftSkipsStringLiterals() {
        assertEquals("\"A1\"+B2", ExcelFormula.shift("\"A1\"+A1", 1, 1))
    }

    @Test fun formulaToOdfBasic() {
        assertEquals("of:=SUM([.A1:.A3])+[.B1]", ExcelFormula.toOdf("SUM(A1:A3)+B1"))
    }

    @Test fun formulaRoundTripWholeColumn() {
        val excel = "SUM(A:A)"
        val odf = ExcelFormula.toOdf(excel)
        assertEquals("=$excel", ExcelFormula.fromOdf(odf))
    }

    @Test fun formulaFromOdfPassThrough() {
        assertEquals("=1+2", ExcelFormula.fromOdf("of:=1+2"))
        assertEquals("=1,2", ExcelFormula.fromOdf("of:=1;2"))
    }

    @Test fun formulaMaxColLetters() {
        assertEquals("XFD", ExcelFormula.MAX_COL_LETTERS)
        assertEquals(1048576, ExcelFormula.MAX_ROWS)
        assertEquals(16384, ExcelFormula.MAX_COLS)
    }
}
