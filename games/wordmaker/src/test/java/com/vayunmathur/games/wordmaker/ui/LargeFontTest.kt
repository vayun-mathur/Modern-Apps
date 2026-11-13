package com.vayunmathur.games.wordmaker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.ui.components.CrosswordBoard
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Letters on the wheel and the grid must not clip at 200% font. Native graphics, so text is
 * measured with real font metrics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class LargeFontTest {
    @get:Rule val rule = createComposeRule()

    private fun setAtDoubleFont(content: @Composable () -> Unit) {
        rule.setContent {
            DynamicTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) { content() }
            }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onAllNodesWithText(text)[0].fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    /**
     * The glyph's laid-out line must fit the tile it was measured in: its height (line height
     * included) within the height constraint, and its unwrapped width within the width one.
     * hasVisualOverflow is not used: the semantics layout result reports the paragraph at the
     * full constraint width, so it flags any glyph narrower than its tile.
     */
    private fun assertFits(text: String, what: String) {
        val layout = layoutOf(text)
        val input = layout.layoutInput
        val paragraph = layout.multiParagraph
        val fits = paragraph.height <= input.constraints.maxHeight &&
            paragraph.intrinsics.maxIntrinsicWidth <= input.constraints.maxWidth
        assertTrue(
            fits,
            "$what clipped at 200% font: constraints=${input.constraints} " +
                "height=${paragraph.height} width=${paragraph.intrinsics.maxIntrinsicWidth} " +
                "fontSize=${input.style.fontSize} lineHeight=${input.style.lineHeight} " +
                "fontScale=${input.density.fontScale}",
        )
    }

    @Test fun wheelLettersFitTheirTilesAtDoubleFont() {
        setAtDoubleFont {
            LetterChooser(
                letters = listOf(ChooserLetter(0, 'W'), ChooserLetter(1, 'M'), ChooserLetter(2, 'G')),
                tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                onShuffle = {}, onWordSubmitted = { _, _ -> },
                onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
            )
        }
        assertFits("W", "wheel letter")
        assertFits("M", "wheel letter")
    }

    @Test fun gridLettersFitTheirCellsAtDoubleFont() {
        setAtDoubleFont {
            CrosswordBoard(
                foundWords = setOf("CAT"), revealedHints = emptySet(),
                crosswordData = CrosswordData.fromString("CAT\n C \n T ")!!, wordToAnimate = null,
                onCellPositioned = { _, _ -> }, onCellClicked = { _, _ -> }, scaleUpdated = {},
            )
        }
        assertFits("C", "grid letter")
        assertFits("A", "grid letter")
    }
}
