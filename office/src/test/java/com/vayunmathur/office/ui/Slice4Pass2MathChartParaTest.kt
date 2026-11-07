package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 4 pass 2: MathView node tree, OdfChartView chart types, ParagraphView span machinery. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2MathChartParaTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ----- pure helpers -----

    @Test fun formatAxisWholeAndFractional() {
        assertEquals("3", formatAxis(3f))
        assertEquals("-2", formatAxis(-2f))
        assertEquals("0", formatAxis(0f))
        assertEquals("3.5", formatAxis(3.5f))
        assertEquals("-0.5", formatAxis(-0.5f))
    }

    @Test fun evalCondFormatNumericOps() {
        fun rule(cond: String) = com.vayunmathur.library.ui.odf.OdfCondFormat(cond)
        val rules = listOf(rule("value() < 0"), rule("value() >= 100"), rule("cell-content() = 42"))
        assertEquals(rules[0], evalCondFormat(rules, -1.0, "-1"))
        assertEquals(rules[1], evalCondFormat(rules, 100.0, "100"))
        assertEquals(rules[2], evalCondFormat(rules, 42.0, "42"))
        assertNull(evalCondFormat(rules, 50.0, "50"))
        assertNull(evalCondFormat(rules, null, "50"), "numeric comparisons need a numeric cell")
    }

    @Test fun evalCondFormatStringAndMalformed() {
        fun rule(cond: String) = com.vayunmathur.library.ui.odf.OdfCondFormat(cond)
        val eq = listOf(rule("cell-content() = \"done\""))
        assertNotNull(evalCondFormat(eq, null, "done"))
        assertNull(evalCondFormat(eq, null, "pending"))
        val ne = listOf(rule("cell-content() <> \"done\""))
        assertNotNull(evalCondFormat(ne, null, "pending"))
        assertNull(evalCondFormat(listOf(rule("not a condition"), rule("value() > x")), 5.0, "5"))
        assertNull(evalCondFormat(emptyList(), 5.0, "5"))
        val le = listOf(rule("value() <= 5"), rule("value() <> 5"))
        assertEquals(le[0], evalCondFormat(le, 5.0, "5"))
        assertEquals(le[1], evalCondFormat(listOf(rule("value() <> 5")), 6.0, "6"))
    }

    // ----- MathView -----

    @Test fun mathViewRendersEveryNodeKind() {
        val mathml = "<math>" +
            "<mfrac><mi>x</mi><mn>2</mn></mfrac>" +
            "<msup><mi>y</mi><mn>3</mn></msup>" +
            "<msub><mi>z</mi><mn>1</mn></msub>" +
            "<msubsup><mi>w</mi><mn>0</mn><mn>9</mn></msubsup>" +
            "<msqrt><mi>r</mi></msqrt>" +
            "<mroot><mi>s</mi><mn>4</mn></mroot>" +
            "<mfenced><mi>f</mi></mfenced>" +
            "<munder><mi>u</mi><mn>5</mn></munder>" +
            "<mover><mi>o</mi><mn>6</mn></mover>" +
            "<munderover><mi>q</mi><mn>7</mn><mn>8</mn></munderover>" +
            "<mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr>" +
            "<mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable>" +
            "<mmultiscripts><mi>m</mi><mn>1</mn><mn>2</mn><mprescripts/><mn>3</mn><mn>4</mn></mmultiscripts>" +
            "<mo>+</mo><mn>42</mn>" +
            "</math>"
        rule.setContent { MathView(mathml) }
        rule.onNodeWithText("x", substring = true).assertExists()
        rule.onNodeWithText("42", substring = true).assertExists()
        rule.onRoot().captureToImage()
    }

    @Test fun mathViewFallbackForUnparseable() {
        rule.setContent { MathView("not mathml at all <<<") }
        rule.onNodeWithText("not mathml", substring = true).assertExists()
    }

    // ----- OdfChartView -----

    private fun chart(type: ChartType, values: List<Float> = listOf(3f, -2f, 5f, 0f)) = OdfChart(
        type = type,
        categories = listOf("Q1", "Q2", "Q3", "Q4"),
        series = listOf(
            OdfChartSeries("Alpha", values),
            OdfChartSeries("Beta", listOf(1f, 4f, 2f, 6f)),
        ),
        title = "Sales",
    )

    @Test fun chartViewRendersEveryTypeAndDraws() {
        var current by mutableStateOf(chart(ChartType.BAR))
        var clicks = 0
        rule.setContent { OdfChartView(current) { clicks++ } }
        for (type in ChartType.entries) {
            current = chart(type)
            rule.onNodeWithText("Sales").assertExists()
            rule.onNodeWithText("Alpha").assertExists()
            rule.onRoot().captureToImage()
        }
        assertEquals(0, clicks)
        rule.onNodeWithText("Sales").performClick()
        assertEquals(1, clicks)
    }

    @Test fun chartViewEdgeCases() {
        var current by mutableStateOf(
            OdfChart(ChartType.PIE, emptyList(), emptyList())
        )
        rule.setContent { OdfChartView(current) }
        rule.onRoot().captureToImage()
        current = OdfChart(ChartType.LINE, listOf("only"), listOf(OdfChartSeries("Solo", listOf(-5f))))
        rule.onNodeWithText("Solo").assertExists()
        rule.onRoot().captureToImage()
        current = OdfChart(ChartType.DONUT, listOf("a"), listOf(OdfChartSeries("D", listOf(0f))), title = null)
        rule.onRoot().captureToImage()
    }

    // ----- ParagraphView -----

    @Test fun paragraphViewAllStylesAndSpanFeatures() {
        rule.setContent {
            Column {
                for (style in ParagraphStyle.entries) {
                    ParagraphView(OdfParagraph(listOf(OdfSpan("para-$style")), style = style))
                }
                ParagraphView(
                    OdfParagraph(
                        listOf(
                            OdfSpan("bold", bold = true),
                            OdfSpan("ital", italic = true),
                            OdfSpan("under", underline = true, strikethrough = true),
                            OdfSpan("colored", color = 0xFF0000L, backgroundColor = 0x00FF00L, fontSize = 20f),
                            OdfSpan("super", superscript = true),
                            OdfSpan("sub", subscript = true),
                            OdfSpan("LOUD", textTransform = "lowercase"),
                            OdfSpan("quiet", textTransform = "uppercase"),
                            OdfSpan("two words", textTransform = "capitalize"),
                        ),
                        marginLeft = 24f, marginTop = 8f, marginBottom = 9f, listLevel = 3,
                        borderColor = 0x123456L, backgroundColor = 0xABCDEFL, lineHeightPercent = 1.5f,
                        alignment = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    fontSizeMultiplier = 1.25f,
                    nightTextColor = Color.White,
                )
            }
        }
        rule.onNodeWithText("loud", substring = true).assertExists()
        rule.onNodeWithText("QUIET", substring = true).assertExists()
        rule.onNodeWithText("super", substring = true).assertExists()
    }

    @Test fun paragraphViewSearchHighlightAndPlain() {
        rule.setContent {
            ParagraphView(
                OdfParagraph(listOf(OdfSpan("Hello HELLO world"))),
                searchQuery = "hello",
            )
        }
        rule.onNodeWithText("Hello", substring = true).assertExists()
    }

    @Test fun paragraphViewLinkAndAnnotationSpansRender() {
        val rich = OdfParagraph(
            listOf(
                OdfSpan("linked ".repeat(12), href = "https://example.org/page"),
                OdfSpan(
                    "annotated word ".repeat(8),
                    annotation = OdfAnnotation(
                        author = "Bob",
                        paragraphs = listOf(OdfParagraph(listOf(OdfSpan("popup body")))),
                    ),
                ),
            )
        )
        rule.setContent { ParagraphView(rich) }
        rule.onNodeWithText("linked", substring = true).assertExists()
        rule.onNodeWithText("annotated", substring = true).assertExists()
        // Clicking the text exercises the ClickableText handler (URL lookup first,
        // then annotation toggle); the rendered spans are the stable assertion.
        rule.onNodeWithText("annotated", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("linked", substring = true).assertExists()
    }
}
