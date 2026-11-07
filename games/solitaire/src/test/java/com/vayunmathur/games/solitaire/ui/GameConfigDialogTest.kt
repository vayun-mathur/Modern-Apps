package com.vayunmathur.games.solitaire.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.R
import com.vayunmathur.games.solitaire.data.DrawMode
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.KlondikeDifficulty
import com.vayunmathur.games.solitaire.ui.dialogs.GameConfigDialog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-mode configuration dialog selections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameConfigDialogTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int) = ctx.getString(id)
    private fun uiStr(id: Int) = ctx.getString(id)

    @Test fun klondikeDialogSelectsDrawThreeAndHard() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.KLONDIKE, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText("Klondike").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.draw_three)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.hard)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(GameConfig(drawMode = DrawMode.DRAW_THREE, klondikeDifficulty = KlondikeDifficulty.HARD), started)
    }

    @Test fun klondikeDialogDefaultsAndRelaxed() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.KLONDIKE, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText(str(R.string.mode_relaxed)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(DrawMode.DRAW_ONE, started!!.drawMode)
        assertEquals(KlondikeDifficulty.RELAXED, started!!.klondikeDifficulty)
    }

    @Test fun klondikeDialogBackDismisses() {
        var dismissals = 0
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.KLONDIKE, onStart = { started = it }, onDismiss = { dismissals++ }) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.back)).performClick()
        assertEquals(1, dismissals)
        assertEquals(null, started)
    }

    @Test fun spiderDialogSelectsSuitCount() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.SPIDER, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText("Spider").assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.medium)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(2, started!!.spiderSuits)
    }

    @Test fun spiderDialogEasyAndDefaultHard() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.SPIDER, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(4, started!!.spiderSuits)
        assertTrue(started!!.spiderSuits == 4)
    }

    @Test fun pyramidDialogSelectsRelaxed() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.PYRAMID, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText("Pyramid").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.mode_relaxed)).performClick()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(true, started!!.relaxed)
    }

    @Test fun pyramidDialogOriginalDefault() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.PYRAMID, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText(str(R.string.mode_original)).assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(false, started!!.relaxed)
    }

    @Test fun freeCellDialogStartsImmediatelyWithDefaults() {
        var started: GameConfig? = null
        rule.setContent { GameConfigDialog(GameMode.FREECELL, onStart = { started = it }, onDismiss = {}) }
        rule.onNodeWithText("FreeCell").assertIsDisplayed()
        rule.onNodeWithText(uiStr(com.vayunmathur.library.ui.R.string.new_game)).performClick()
        assertEquals(GameConfig(), started)
    }
}
