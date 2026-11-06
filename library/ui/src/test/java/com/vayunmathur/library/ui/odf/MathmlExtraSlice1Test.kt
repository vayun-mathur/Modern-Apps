package com.vayunmathur.library.ui.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MathML converter round two: sub-superscripts, tables, under/over, styles,
 * roots and unknown elements. Slice-1 coverage for library/ui (odf package).
 */
class MathmlExtraSlice1Test {

    @Test
    fun `subsup and underover convert`() {
        val msubsup = "<math><msubsup><mi>x</mi><mn>1</mn><mn>2</mn></msubsup></math>"
        assertEquals("{x}_{1}^{2}", MathmlToLatex.convert(msubsup))
        val munderover = "<math><munderover><mo>∑</mo><mi>i</mi><mi>n</mi></munderover></math>"
        assertEquals("\\sum_{i}^{n}", MathmlToLatex.convert(munderover))
    }

    @Test
    fun `tables become matrices`() {
        val mtable = "<math><mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr>" +
            "<mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable></math>"
        val latex = MathmlToLatex.convert(mtable)
        assertTrue(latex.contains("\\begin{matrix}"), latex)
        assertTrue(latex.contains("1 & 2"), latex)
        assertTrue(latex.contains("\\\\"), latex)
        assertTrue(latex.contains("\\end{matrix}"), latex)
    }

    @Test
    fun `styled rows and nth roots convert`() {
        val styled = "<math><mstyle><mrow><mi>y</mi></mrow></mstyle></math>"
        assertEquals("y", MathmlToLatex.convert(styled))
        val plainRow = "<mrow><mi>z</mi></mrow>"
        assertEquals("z", MathmlToLatex.convert(plainRow))
    }

    @Test
    fun `more operators map`() {
        // ∫ (U+222B) -> \int, ∞ (U+221E) -> \infty.
        assertEquals("\\int", MathmlToLatex.convert("<math><mo>∫</mo></math>"))
        assertEquals("\\infty", MathmlToLatex.convert("<math><mo>∞</mo></math>"))
        // × (U+00D7) -> \times, ≤ (U+2264) -> \leq, → (U+2192) -> \to.
        assertEquals("\\times", MathmlToLatex.convert("<math><mo>×</mo></math>"))
        assertEquals("\\leq", MathmlToLatex.convert("<math><mo>≤</mo></math>"))
        assertEquals("\\to", MathmlToLatex.convert("<math><mo>→</mo></math>"))
    }

    @Test
    fun `unknown elements fall through to their children`() {
        val latex = MathmlToLatex.convert("<math><mystery><mi>q</mi></mystery></math>")
        assertEquals("q", latex)
    }

    @Test
    fun `nested fractions convert`() {
        val nested = "<math><mfrac><mrow><mi>a</mi><mo>+</mo><mi>b</mi></mrow><mn>2</mn></mfrac></math>"
        assertEquals("\\frac{a + b}{2}", MathmlToLatex.convert(nested))
    }

    @Test
    fun `a formula with sqrt lands in the latex export`() {
        val mathml = "<math><msqrt><mfrac><mn>1</mn><mn>2</mn></mfrac></msqrt></math>"
        val tex = LatexExporter.export(
            OdfDocument.TextDocument(
                title = "T",
                content = listOf(OdfContentBlock.Formula(mathml)),
            )
        )
        assertTrue(tex.contains("\\sqrt{\\frac{1}{2}}"), tex)
    }
}
