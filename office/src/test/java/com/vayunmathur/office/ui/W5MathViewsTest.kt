package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W5: [MathView] over every MathML construct the node renderer supports —
 * tables, multiscripts, under/over, fenced with custom delimiters, roots —
 * plus the unparseable fallback that shows the raw markup. Assertions check
 * the rendered token text for each construct.
 */
@RunWith(RobolectricTestRunner::class)
class W5MathViewsTest {

    @get:Rule val rule = createComposeRule()

    @Test fun everyMathConstructRenders() {
        val samples = listOf(
            "<math><mtable><mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr>" +
                "<mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr></mtable></math>" to "a",
            "<math><munderover><mo>∑</mo><mi>i</mi><mi>n</mi></munderover></math>" to "∑",
            "<math><munder><mi>x</mi><mi>u</mi></munder></math>" to "x",
            "<math><mover><mi>y</mi><mi>o</mi></mover></math>" to "y",
            "<math><mfenced open=\"[\" close=\"]\"><mi>z</mi></mfenced></math>" to "z",
            "<math><mfenced><mi>w</mi></mfenced></math>" to "w",
            "<math><mroot><mi>r</mi><mn>5</mn></mroot></math>" to "r",
            "<math><mmultiscripts><mi>m</mi><mi>i</mi><mi>j</mi></mmultiscripts></math>" to "m",
            "<math><mfrac><mi>p</mi><mi>q</mi></mfrac></math>" to "p",
            "<math><msubsup><mi>s</mi><mi>a</mi><mi>b</mi></msubsup></math>" to "s",
            "<math><semantics><mi>t</mi></semantics></math>" to "t",
            "<math><mstyle><mi>v</mi></mstyle></math>" to "v",
        )
        rule.setContent {
            Column {
                for ((mathml, _) in samples) MathView(mathml)
            }
        }
        for ((_, token) in samples) {
            assertTrue(
                rule.onAllNodesWithText(token, substring = true).fetchSemanticsNodes().isNotEmpty(),
                "token $token must render",
            )
        }
    }

    @Test fun unparseableMathFallsBackToRawText() {
        rule.setContent { MathView("<math><unknown") }
        rule.onNodeWithText("<math>", substring = true).assertExists()
    }

    @Test fun emptyAndTextOnlyMath() {
        rule.setContent {
            Column {
                MathView("")
                MathView("<math><mtext>hello math</mtext></math>")
            }
        }
        rule.onNodeWithText("hello math", substring = true).assertExists()
    }
}
