package com.vayunmathur.games.unblockjam.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.unblockjam.data.Block
import com.vayunmathur.games.unblockjam.data.Coord
import com.vayunmathur.games.unblockjam.data.Dimension
import com.vayunmathur.games.unblockjam.data.LevelData
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The target block must differ from the others by more than its colour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TargetBlockMarkTest {
    @get:Rule val rule = createComposeRule()

    private val level = LevelData(
        id = "mark", dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = listOf(
            Block(Coord(0, 2), Dimension(2, 1), false), // target
            Block(Coord(0, 4), Dimension(2, 1), false), // same shape, ordinary
        ),
        optimalMoves = 1,
    )

    /** Pixels close to white, the default palette's onTertiary that the mark is drawn in. */
    private fun markPixels(tag: String): Int {
        val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
        var count = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (near(c, Color.White)) count++
        }
        return count
    }

    private fun near(a: Color, b: Color) =
        abs(a.red - b.red) < 0.1f && abs(a.green - b.green) < 0.1f && abs(a.blue - b.blue) < 0.1f

    @Test
    fun targetBlockCarriesTheExitMarkAndOthersDoNot() {
        rule.setContent {
            UnblockJamTheme {
                Box(Modifier.size(360.dp)) { GameBoard(level, {}, {}, isLevelWon = false) }
            }
        }
        assertTrue(markPixels("block_0") > 20, "target block should carry a visible mark")
        assertEquals(0, markPixels("block_1"), "ordinary blocks stay plain")
    }
}
