package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressCodecTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private val cells = buildSet { for (r in 0 until 5) for (c in 0 until 5) add(p(r, c)) }
    private val level = LevelData(
        "c", 5, 5, cells, computeAdjacency(cells), null,
        listOf(EndpointPair(0, listOf(p(0, 0), p(4, 0))), EndpointPair(3, listOf(p(2, 0), p(2, 4)))),
        setOf(p(2, 2)), 2,
    )

    private fun state(paths: Map<Int, List<CellPos>>, prior: Map<CellPos, Int> = emptyMap()) =
        BoardState(paths, BoardEdits.owners(level, paths, prior))

    @Test fun roundTripKeepsBoardHistoryAndBridgeOwner() {
        val blue = listOf(p(2, 4), p(2, 3), p(2, 2), p(2, 1))
        val red = listOf(p(0, 0), p(0, 1), p(0, 2), p(1, 2), p(2, 2), p(3, 2))
        // Red drawn second, but red owns the bridge here: the saved owner must win over the default.
        val board = state(mapOf(3 to blue, 0 to red), prior = mapOf(p(2, 2) to 0))
        val progress = SavedProgress(board, listOf(BoardState(), state(mapOf(3 to blue))))
        val decoded = ProgressCodec.decode(ProgressCodec.encode(progress), level)
        assertEquals(progress, decoded)
        assertEquals(0, decoded!!.state.cellOwner[p(2, 2)])
    }

    @Test fun emptyProgressRoundTrips() {
        val empty = SavedProgress(BoardState(), emptyList())
        assertEquals(empty, ProgressCodec.decode(ProgressCodec.encode(empty), level))
    }

    @Test fun progressThatDoesNotFitTheLevelIsDropped() {
        fun json(path: String, color: Int = 0) = """{"v":1,"s":{"p":{"$color":[$path]},"o":{}},"h":[]}"""
        assertEquals(listOf(p(0, 0), p(0, 1)), ProgressCodec.decode(json("0,0,0,1"), level)!!.state.paths[0])
        assertNull(ProgressCodec.decode(json("0,0,0,9"), level)) // off the board
        assertNull(ProgressCodec.decode(json("0,0,0,2"), level)) // not a step
        assertNull(ProgressCodec.decode(json("0,0,0,1,0,0"), level)) // revisits a cell
        assertNull(ProgressCodec.decode(json("0,0"), level)) // too short
        assertNull(ProgressCodec.decode(json("0,0,0,1", color = 7), level)) // unknown colour
        assertNull(ProgressCodec.decode(json("1,0,2,0"), level)) // onto another colour's dot
        assertNull(ProgressCodec.decode(json("0,0,0"), level)) // odd coordinate count
        assertNull(ProgressCodec.decode("not json", level))
        assertNull(ProgressCodec.decode("""{"v":2,"s":{"p":{}},"h":[]}""", level))
    }
}
