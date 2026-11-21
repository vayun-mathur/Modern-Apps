package com.vayunmathur.games.minesweeper.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinesweeperProgressCodecTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val config = GameConfig(BoardSize.SMALL, Difficulty.EASY)

    private fun midGame(): SavedMinesweeperGame {
        val base = MinesweeperGameState.empty(config)
        val cells = base.cellCount
        // Ten mines placed clear of the revealed corner, matching EASY on SMALL (8 mines -> use count below).
        val mines = List(cells) { it in setOf(20, 21, 30, 40, 50, 60, 70, 79) }
        val counts = List(cells) { index ->
            if (mines[index]) 0 else neighbourIndices(base.cols, base.rows, index).count { mines[it] }
        }
        val game = base.copy(
            mines = mines,
            neighbourCounts = counts,
            revealed = List(cells) { it == 0 || it == 1 },
            flagged = List(cells) { it == 20 },
            started = true,
        )
        return SavedMinesweeperGame(game, elapsedSeconds = 42, tapMode = TapMode.FLAG, placedAnyFlag = true)
    }

    @Test
    fun midGameRoundTripsThroughCodec() {
        val saved = midGame()
        assertEquals(saved, MinesweeperProgressCodec.decode(MinesweeperProgressCodec.encode(saved), config))
    }

    @Test
    fun mismatchedConfigAndCorruptJsonAreDropped() {
        val encoded = MinesweeperProgressCodec.encode(midGame())
        assertNull(MinesweeperProgressCodec.decode(encoded, GameConfig(BoardSize.MEDIUM, Difficulty.EASY)))
        assertNull(MinesweeperProgressCodec.decode(encoded, GameConfig(BoardSize.SMALL, Difficulty.HARD)))
        assertNull(MinesweeperProgressCodec.decode("not json", config))
        assertNull(MinesweeperProgressCodec.decode("""{"v":2}""", config))
    }

    @Test
    fun midGameRoundTripsThroughStore() {
        val store = InProgressField(app)
        store.clear(config.variant)
        val saved = midGame()
        store.save(config, saved)
        assertEquals(saved, assertNotNull(InProgressField(app).load(config)))
        assertNull(store.load(GameConfig(BoardSize.SMALL, Difficulty.HARD)))
        store.clear(config.variant)
        assertNull(store.load(config))
    }
}
