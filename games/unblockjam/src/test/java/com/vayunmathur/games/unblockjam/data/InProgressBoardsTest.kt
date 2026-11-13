package com.vayunmathur.games.unblockjam.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class InProgressBoardsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun level(id: String = "p1", vararg extra: Block) = LevelData(
        id = id, dimension = Dimension(6, 6), exit = Coord(6, 2),
        blocks = listOf(
            Block(Coord(0, 2), Dimension(2, 1), false),
            Block(Coord(3, 1), Dimension(1, 2), false),
            Block(Coord(5, 5), Dimension(1, 1), true),
        ) + extra,
        optimalMoves = 1,
    )

    private fun LevelData.moving(index: Int, to: Coord) =
        copy(blocks = blocks.toMutableList().also { it[index] = it[index].copy(position = to) }, lastMovedBlockIndex = index)

    @Test
    fun roundTripsTheBoardAndItsHistory() {
        val store = InProgressBoards(app)
        val start = level()
        val one = start.moving(1, Coord(3, 3))
        val two = one.moving(0, Coord(1, 2))
        store.save(two, listOf(start, one))
        val restored = assertNotNull(store.load(start))
        assertEquals(two, restored.current)
        assertEquals(listOf(start, one), restored.history)
    }

    @Test
    fun ignoresSavesThatNoLongerFitTheLevel() {
        val store = InProgressBoards(app)
        val start = level()
        store.save(start.moving(1, Coord(3, 3)), listOf(start))
        // The level gained a block since (a pack update): the save is ignored, not misapplied.
        assertNull(store.load(level("p1", Block(Coord(0, 0), Dimension(1, 1), true))))
        // Off-axis and overlapping saves are refused too.
        store.save(start.moving(1, Coord(4, 1)), listOf(start))
        assertNull(store.load(start))
        store.save(start.moving(0, Coord(2, 2)), listOf(start))
        assertNull(store.load(start))
    }

    @Test
    fun clearAndDailyPruning() {
        val store = InProgressBoards(app)
        val today = level("daily_100_0")
        val yesterday = level("daily_99_0")
        val pack = level("orig_1")
        for (l in listOf(today, yesterday, pack)) store.save(l.moving(1, Coord(3, 3)), listOf(l))
        store.retainDaily(setOf("daily_100_0"))
        assertNotNull(store.load(today))
        assertNull(store.load(yesterday))
        assertNotNull(store.load(pack), "pack boards are not dailies")
        store.clear("orig_1")
        assertNull(store.load(pack))
    }
}
