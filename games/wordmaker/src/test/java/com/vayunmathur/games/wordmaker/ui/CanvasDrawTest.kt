package com.vayunmathur.games.wordmaker.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.ui.components.LetterChooser
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The letter trail is drawn on a Canvas, which Robolectric's legacy graphics
 * never rasterises: only native graphics executes the draw block. This class
 * runs in native mode so the trail drawing itself is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class CanvasDrawTest {
    @get:Rule val rule = createComposeRule()

    @Test fun draggingAcrossLettersDrawsTheTrail() {
        val submitted = mutableListOf<String>()
        val letters = listOf(
            ChooserLetter(0, 'C'), ChooserLetter(1, 'A'),
            ChooserLetter(2, 'T'), ChooserLetter(3, 'S'),
        )
        rule.setContent {
            DynamicTheme {
                LetterChooser(
                    letters = letters, tapToSpell = false, wheelSpacing = WheelSpacing.DEFAULT,
                    onShuffle = {}, onWordSubmitted = { w, _ -> submitted += w },
                    onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                )
            }
        }
        fun centerOf(c: Char): Offset =
            rule.onNodeWithText(c.toString()).fetchSemanticsNode().boundsInRoot.center
        val c = centerOf('C')
        val a = centerOf('A')
        val t = centerOf('T')
        // Hold the drag open across two letters so a frame is drawn with both the
        // letter-to-letter trail and the live segment to the finger.
        rule.onRoot().performTouchInput {
            down(c)
            moveTo(c + Offset(0f, 12f))
            moveTo(a)
            moveTo(t)
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals(listOf("CAT"), submitted)
    }
}
