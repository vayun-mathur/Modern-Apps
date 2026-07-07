package com.vayunmathur.games.solitaire

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:solitaire:metadata`. Captures a dealt board for
 * each of the three game types: Klondike, Spider, and FreeCell.
 *
 * The game screen runs a 1s timer that keeps Compose perpetually busy, so the clock is only
 * paused while a board is on screen (to let captures settle). The Home screen and the
 * New Game dialog have no such timer but do play navigation/dialog animations, so the clock
 * must be auto-advancing there — otherwise a paused nav transition leaves buttons frozen
 * mid-slide and taps miss them.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    /** Captures a board: pauses the perpetual game timer, settles, then snaps. Leaves the
     * clock paused since giveUp() runs next on the still-busy game screen. */
    private fun snapBoard(index: Int) {
        composeRule.mainClock.autoAdvance = false
        composeRule.mainClock.advanceTimeBy(1500)
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    /**
     * Opens the New Game dialog and starts the game whose button matches [buttonText]. Runs
     * with the clock auto-advancing so the dialog and the nav transition into the board settle.
     */
    private fun startGame(buttonText: String) {
        composeRule.onNodeWithText(ctx.getString(R.string.new_game)).performClick()
        composeRule.waitForIdle()
        // The mode names also appear on the home stats cards, so match the clickable
        // dialog button specifically (substring for Klondike's "Draw 1" variant).
        composeRule.onNode(hasText(buttonText, substring = true) and hasClickAction()).performClick()
    }

    /** Gives up the current board (clock paused so waitForIdle doesn't hang on the timer). */
    private fun giveUp() {
        composeRule.onNodeWithText(ctx.getString(R.string.give_up)).performClick()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun generateStoreScreenshots() {
        startGame(ctx.getString(R.string.draw_one))
        snapBoard(1) // Klondike board

        giveUp()
        startGame(ctx.getString(R.string.spider))
        snapBoard(2) // Spider board

        giveUp()
        startGame(ctx.getString(R.string.freecell))
        snapBoard(3) // FreeCell board
    }
}
