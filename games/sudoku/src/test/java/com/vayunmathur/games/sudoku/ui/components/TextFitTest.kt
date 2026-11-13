package com.vayunmathur.games.sudoku.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.sudoku.R
import com.vayunmathur.games.sudoku.data.BoardSize
import com.vayunmathur.games.sudoku.data.Difficulty
import com.vayunmathur.games.sudoku.data.SudokuGameState
import com.vayunmathur.games.sudoku.domain.SudokuGenerator
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Board glyphs follow the font scale only until they fill their fixed-size box. */
@RunWith(RobolectricTestRunner::class)
class TextFitTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun preferredSizeIsKeptAtNormalScale() {
        val density = Density(density = 2.625f, fontScale = 1f)
        assertEquals(19.sp, density.fitSp(19.sp, 21.dp))
    }

    @Test
    fun largeFontScaleIsCappedToTheBox() {
        val density = Density(density = 2.625f, fontScale = 2f)
        val fitted = density.fitSp(19.sp, 21.dp)
        // Rendered height in px equals the cap, not 2 x 19sp.
        with(density) { assertEquals(21.dp.toPx(), fitted.toPx(), 0.01f) }
    }

    @Test
    fun scaleBelowTheCapStillGrows() {
        val density = Density(density = 2f, fontScale = 1.1f)
        assertEquals(11.sp, density.fitSp(11.sp, 20.dp), "a modest scale is honoured")
    }

    @Test
    fun boardAndPadRenderAtDoubleFontScale() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val base = SudokuGameState.from(SudokuGenerator.generate(BoardSize.TWELVE, Difficulty.EASY, Random(5)))
        val blank = base.givens.indices.first { base.givens[it] == 0 }
        val game = base.copy(notes = base.notes.toMutableList().also { it[blank] = 0xFFF })
        rule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale = 2f)) {
                androidx.compose.foundation.layout.Column {
                    SudokuGrid(game = game, onSelect = {})
                    NumberPad(game = game, onDigit = {})
                    NumberPad(game = game.copy(notesMode = true), onDigit = {})
                }
            }
        }
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_digit_key, "C")).assertExists()
        rule.onNodeWithContentDescription(ctx.getString(R.string.cd_note_key, "C")).assertExists()
    }
}
