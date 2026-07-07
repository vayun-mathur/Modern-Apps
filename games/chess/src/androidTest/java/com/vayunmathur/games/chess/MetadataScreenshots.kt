package com.vayunmathur.games.chess

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.games.chess.data.Position
import com.vayunmathur.games.chess.util.ChessViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:games:chess:metadata`. Store screenshots lead with
 * gameplay (a mid-game board), followed by the mode-selection dialog.
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
        // App launches with the New Game dialog up (offers 2-player / vs-AI) — keep it
        // as the second shot advertising the modes.
        snap(2)

        // Start a local 2-player game, then play an Italian-game opening so the board
        // shows real gameplay rather than the plain starting position.
        composeRule.onNodeWithText(ctx.getString(R.string.two_player_local)).performClick()
        composeRule.waitForIdle()

        val viewModel = ViewModelProvider(composeRule.activity)[ChessViewModel::class.java]
        // Row 0 is Black's back rank, row 7 is White's; White moves first.
        val opening = listOf(
            Position(6, 4) to Position(4, 4), // e2-e4
            Position(1, 4) to Position(3, 4), // e7-e5
            Position(7, 6) to Position(5, 5), // Ng1-f3
            Position(0, 1) to Position(2, 2), // Nb8-c6
            Position(7, 5) to Position(4, 2), // Bf1-c4
            Position(0, 6) to Position(2, 5), // Ng8-f6
        )
        composeRule.runOnUiThread {
            for ((from, to) in opening) {
                viewModel.onSquareClick(from)
                viewModel.onSquareClick(to)
            }
        }
        composeRule.waitForIdle()
        snap(1)
    }
}
