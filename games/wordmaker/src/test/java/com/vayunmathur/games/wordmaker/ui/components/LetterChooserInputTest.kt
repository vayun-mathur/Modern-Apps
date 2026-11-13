package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.ui.ChooserLetter
import com.vayunmathur.library.ui.DynamicTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Gesture-level tests for the letter wheel: real touch streams through the wheel's
 * pointer handling, aimed at where the letters are actually drawn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LetterChooserInputTest {
    @get:Rule val rule = createComposeRule()

    // Four letters: C at the top, A on the right, T at the bottom, S on the left (in LTR).
    private val letters = listOf(
        ChooserLetter(0, 'C'), ChooserLetter(1, 'A'), ChooserLetter(2, 'T'), ChooserLetter(3, 'S'),
    )

    private fun setChooser(
        tapToSpell: Boolean = false,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        spacing: WheelSpacing = WheelSpacing.DEFAULT,
        onSubmit: suspend (String) -> Unit,
    ) {
        rule.setContent {
            DynamicTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    LetterChooser(
                        letters = letters, tapToSpell = tapToSpell, wheelSpacing = spacing,
                        onShuffle = {}, onWordSubmitted = { w, _ -> onSubmit(w) },
                        onWordBoxPositioned = {}, onLetterPositioned = { _, _ -> },
                    )
                }
            }
        }
    }

    /** Where [c] is drawn, in root coordinates. Read before any word box shows letters. */
    private fun centerOf(c: Char): Offset =
        rule.onNodeWithText(c.toString()).fetchSemanticsNode().boundsInRoot.center

    @Test fun rejectedSwipeThenImmediateSwipeKeepsTheNewSwipe() {
        val verdict = CompletableDeferred<Unit>()
        val submitted = mutableListOf<String>()
        // The first word is "being judged" (shaking) until the test releases it.
        setChooser { w -> submitted += w; if (submitted.size == 1) verdict.await() }
        val c = centerOf('C'); val a = centerOf('A'); val t = centerOf('T'); val s = centerOf('S')

        rule.onRoot().performTouchInput { down(c); moveTo(c + Offset(0f, 12f)); moveTo(a); up() }
        rule.waitForIdle()
        assertEquals(listOf("CA"), submitted)

        // Start the next swipe while the first word is still shaking, then let the shake end
        // mid-swipe: the new swipe must keep its first letters.
        rule.onRoot().performTouchInput { down(t); moveTo(t + Offset(0f, -12f)); moveTo(s) }
        verdict.complete(Unit)
        rule.waitForIdle()
        rule.onRoot().performTouchInput { moveTo(c); up() }
        rule.waitForIdle()
        assertEquals(listOf("CA", "TSC"), submitted)
    }

    @Test fun rejectedTapWordThenImmediateTapsKeepTheNewWord() {
        val verdict = CompletableDeferred<Unit>()
        val submitted = mutableListOf<String>()
        setChooser(tapToSpell = true) { w -> submitted += w; if (submitted.size == 1) verdict.await() }

        rule.onNodeWithText("C").performClick()
        rule.onNodeWithText("A").performClick()
        rule.onNodeWithContentDescription("Submit").performClick()
        rule.waitForIdle()
        // Tapping on while "CA" shakes starts a fresh word rather than extending "CA"...
        rule.onNodeWithText("T").performClick()
        verdict.complete(Unit)
        rule.waitForIdle()
        // ...and the end of the shake does not wipe it.
        rule.onNodeWithContentDescription("Submit").performClick()
        rule.waitForIdle()
        assertEquals(listOf("CA", "T"), submitted)
    }
}
