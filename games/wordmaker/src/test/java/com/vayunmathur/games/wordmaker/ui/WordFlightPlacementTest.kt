package com.vayunmathur.games.wordmaker.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntSize
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
import kotlin.test.assertEquals
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
                            animationProgress = Animatable(0f), wordBoxOffset = Offset.Zero, bonusButtonCenter = Offset.Zero, rootOffset = Offset.Zero,
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

    @Test fun bonusFlightOffsetConvertsRootCoordinatesAndCentresOnTheButton() {
        val wordBox = Offset(120f, 900f)
        val bonusCenter = Offset(44f, 1500f)
        val root = Offset(0f, 210f)
        val size = IntSize(80, 40)
        assertEquals(Offset(120f, 690f), bonusFlightOffset(wordBox, bonusCenter, root, size, 0f))
        // Lands with its centre on the button's centre, in the overlay's own space.
        assertEquals(Offset(44f - 40f, 1500f - 210f - 20f), bonusFlightOffset(wordBox, bonusCenter, root, size, 1f))
    }

    @Test fun bonusWordLandsOnTheBonusButtonInsideAnOffsetOverlay() {
        val topInset = 150.dp
        val bonusCenterInRoot = Offset(60f, 600f)
        rule.setContent {
            DynamicTheme {
                // The overlay sits below a bar, like the game screen's body under its top bar.
                Box(Modifier.fillMaxSize().padding(top = topInset)) {
                    val rootOffset = with(LocalDensity.current) { Offset(0f, topInset.toPx()) }
                    WordGameOverlays(
                        showHintDialog = false, onDismissHint = {}, crosswordData = CrosswordData.fromString("CAT\n C \n T ")!!,
                        foundWords = emptySet(), revealedHints = emptySet(), actions = WordGameActions.Noop,
                        showBonusWordsDialog = false, onDismissBonusWords = {}, bonusWords = emptySet(),
                        wordWithDefinition = null, onDismissDefinition = {}, animatedWord = "ZOO",
                        animationProgress = Animatable(1f), wordBoxOffset = Offset(100f, 400f),
                        bonusButtonCenter = bonusCenterInRoot, rootOffset = rootOffset,
                        animatedLetters = emptyList(), scale = 1f,
                    )
                }
            }
        }
        rule.waitForIdle()
        val center = rule.onNodeWithText("ZOO").fetchSemanticsNode().boundsInRoot.center
        assertTrue(
            abs(center.x - bonusCenterInRoot.x) < 2f && abs(center.y - bonusCenterInRoot.y) < 2f,
            "bonus word landed at $center, button centre is $bonusCenterInRoot",
        )
    }
}
