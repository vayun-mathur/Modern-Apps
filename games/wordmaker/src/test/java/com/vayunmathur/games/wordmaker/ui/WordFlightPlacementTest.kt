package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.platform.WordGameActions
import com.vayunmathur.games.wordmaker.ui.components.AnimatedLetter
import com.vayunmathur.library.ui.DynamicTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Where the word-place flights are drawn, in the overlay's (root-anchored) coordinate space. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class WordFlightPlacementTest {
    @get:Rule val rule = createComposeRule()

    private fun setOverlays(layoutDirection: LayoutDirection, letter: AnimatedLetter) {
        rule.setContent {
            DynamicTheme {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Box(Modifier.fillMaxSize()) {
                        WordGameOverlays(
                            showHintDialog = false, onDismissHint = {}, crosswordData = CrosswordData.fromString("CAT\n C \n T ")!!,
                            foundWords = emptySet(), revealedHints = emptySet(), actions = WordGameActions.Noop,
                            showBonusWordsDialog = false, onDismissBonusWords = {}, bonusWords = emptySet(),
                            wordWithDefinition = null, onDismissDefinition = {}, animatedWord = null,
                            animationProgress = Animatable(0f), wordBoxOffset = Offset.Zero, bonusButtonOffset = Offset.Zero,
                            animatedLetters = listOf(letter), scale = 1f,
                        )
                    }
                }
            }
        }
    }

    private fun assertLetterTileAt(topLeft: Offset) {
        val half = with(rule.density) { 35.dp.toPx() } / 2
        val center = rule.onNodeWithText("Q").fetchSemanticsNode().boundsInRoot.center
        val expected = topLeft + Offset(half, half)
        assertTrue(abs(center.x - expected.x) < 2f && abs(center.y - expected.y) < 2f, "letter at $center, expected $expected")
    }

    @Test fun letterFlightLandsOnItsCellInLtr() {
        setOverlays(LayoutDirection.Ltr, AnimatedLetter('Q', Offset(0f, 0f), Offset(40f, 60f), Animatable(1f)))
        assertLetterTileAt(Offset(40f, 60f))
    }

    @Test fun letterFlightLandsOnItsCellInRtl() {
        // Cell positions come from localToRoot, which is never mirrored; the flight must not be either.
        setOverlays(LayoutDirection.Rtl, AnimatedLetter('Q', Offset(0f, 0f), Offset(40f, 60f), Animatable(1f)))
        assertLetterTileAt(Offset(40f, 60f))
    }
}
