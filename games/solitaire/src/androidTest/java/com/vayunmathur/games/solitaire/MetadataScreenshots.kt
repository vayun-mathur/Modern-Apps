package com.vayunmathur.games.solitaire

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
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
 * Screenshot generator driven by `:games:solitaire:metadata`. Leads with the dealt
 * Klondike board, then the home menu with per-mode stats.
 *
 * The game screen runs a 1s timer that keeps Compose perpetually busy, so the test clock
 * is paused (autoAdvance = false) and advanced manually so captures settle.
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

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    @Test
    fun generateStoreScreenshots() {
        composeRule.mainClock.autoAdvance = false

        // Home menu is up at launch — keep it as the second shot.
        snap(2)

        // Start a Klondike (Draw 1) game to lead with the dealt board. Match the unique
        // "Draw 1" substring to avoid depending on the em-dash in the full button label.
        composeRule.onNodeWithText(ctx.getString(R.string.new_game)).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithText(ctx.getString(R.string.draw_one), substring = true).performClick()
        composeRule.mainClock.advanceTimeBy(2000)
        snap(1)
    }
}
