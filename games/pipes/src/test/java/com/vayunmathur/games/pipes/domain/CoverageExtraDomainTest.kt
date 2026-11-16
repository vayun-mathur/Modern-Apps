package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.LevelPack
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoverageExtraDomainTest {
    private fun p(r: Int, c: Int) = CellPos(r, c)
    private fun rect(rows: Int, cols: Int) = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(p(r, c))
    }

    private fun level(
        cells: Set<CellPos> = rect(4, 4),
        adjacency: Map<CellPos, List<CellPos>> = computeAdjacency(cells),
        endpoints: List<EndpointPair> = listOf(
            EndpointPair(0, listOf(p(0, 0), p(0, 3))),
            EndpointPair(1, listOf(p(3, 0), p(3, 3))),
        ),
        bridges: Set<CellPos> = emptySet(),
        renderPositions: Map<CellPos, RenderPos>? = null,
    ) = LevelData("x", 4, 4, cells, adjacency, renderPositions, endpoints, bridges, endpoints.size)

    @Test fun solverRejectsMalformedBoards() {
        val cells = rect(3, 3)
        assertEquals(NumberlinkSolver.Verdict.NONE, NumberlinkSolver.classify(emptySet(), emptyList()))
        assertEquals(NumberlinkSolver.Verdict.NONE, NumberlinkSolver.classify(cells, emptyList()))
        // Triplet endpoints are not a pair the solver can classify.
        assertEquals(
            NumberlinkSolver.Verdict.NONE,
            NumberlinkSolver.classify(cells, listOf(EndpointPair(0, listOf(p(0, 0), p(0, 1), p(0, 2))))),
        )
        // Endpoint outside the board.
        assertEquals(
            NumberlinkSolver.Verdict.NONE,
            NumberlinkSolver.classify(cells, listOf(EndpointPair(0, listOf(p(0, 0), p(9, 9))))),
        )
        // The same cell used by two pairs.
        assertEquals(
            NumberlinkSolver.Verdict.NONE,
            NumberlinkSolver.classify(
                cells,
                listOf(EndpointPair(0, listOf(p(0, 0), p(0, 1))), EndpointPair(1, listOf(p(0, 1), p(2, 2)))),
            ),
        )
        // A board with a hole: the hole cell is skipped, the column flows still hold.
        val holed = rect(2, 2) - p(0, 1)
        assertEquals(
            NumberlinkSolver.Verdict.UNIQUE,
            NumberlinkSolver.classify(holed, listOf(EndpointPair(0, listOf(p(1, 0), p(1, 1))))),
        )
    }

    @Test fun strokeRulesHandleMissingAndUnknownState() {
        val lvl = level()
        assertEquals(emptyList(), StrokeRules.extend(lvl, emptyMap(), 0, emptyList(), p(0, 1)))
        assertEquals(listOf(p(0, 0)), StrokeRules.extend(lvl, emptyMap(), 0, listOf(p(0, 0)), p(9, 9)))
        assertFalse(StrokeRules.isComplete(lvl, 99, listOf(p(0, 0), p(0, 3))))
        assertFalse(StrokeRules.isComplete(lvl, 0, listOf(p(0, 0), p(0, 0))))
        assertFalse(StrokeRules.isComplete(lvl, 0, emptyList()))
        // Reversed pair still completes: first dot is endpoint[1], partner is endpoint[0].
        assertTrue(StrokeRules.isComplete(lvl, 0, listOf(p(0, 3), p(0, 2), p(0, 0))))
        // A path whose last cell has no adjacency entry cannot step anywhere.
        val noAdj = level(adjacency = emptyMap())
        assertFalse(StrokeRules.isValidStep(noAdj, 0, listOf(p(0, 0)), p(0, 1)))
        assertFalse(StrokeRules.isValidStep(lvl, 0, listOf(p(0, 0), p(0, 1)), p(0, 0)))
        // Completing the pair through the intermediate stops the diagonal from overshooting:
        // the direct partner is already the last cell, so extend stays put.
        val done = listOf(p(0, 0), p(0, 1), p(0, 3))
        assertEquals(listOf(p(0, 0), p(0, 1)), StrokeRules.extend(lvl, emptyMap(), 0, listOf(p(0, 0)), p(0, 1)))
        assertFalse(StrokeRules.isComplete(lvl, 0, done.dropLast(1)))
    }

    @Test fun strokeRulesTripletAndBridgeExits() {
        val triplet = level(
            endpoints = listOf(EndpointPair(0, listOf(p(0, 0), p(0, 3), p(3, 3))), EndpointPair(1, listOf(p(3, 0), p(3, 1)))),
        )
        assertFalse(StrokeRules.isComplete(triplet, 0, listOf(p(0, 0), p(0, 3))))
        assertTrue(StrokeRules.isComplete(triplet, 0, listOf(p(0, 0), p(0, 3), p(3, 3))))
        // Bridge at (1,1): a straight vertical run may leave downwards, a turn may not.
        val bridged = level(bridges = setOf(p(1, 1)))
        val entering = listOf(p(0, 1), p(1, 1))
        assertTrue(StrokeRules.isValidStep(bridged, 0, entering, p(2, 1)))
        assertFalse(StrokeRules.isValidStep(bridged, 0, entering, p(1, 2)))
        // A long-range (portal-style) exit from a bridge skips the straight-through rule.
        val portalCells = rect(4, 4)
        val portalAdj = computeAdjacency(portalCells).toMutableMap()
        portalAdj[p(1, 1)] = portalAdj.getValue(p(1, 1)) + p(3, 2)
        val portalLevel = level(adjacency = portalAdj, bridges = setOf(p(1, 1)))
        assertTrue(StrokeRules.isValidStep(portalLevel, 0, entering, p(3, 2)))
        // Diagonal whose only via would complete the stroke mid-way is refused.
        val diag = StrokeRules.extend(level(), emptyMap(), 0, listOf(p(0, 0)), p(1, 1))
        assertEquals(3, diag.size)
        assertEquals(p(1, 1), diag.last())
    }

    @Test fun boardOwnersBridgeFallbacks() {
        val bridged = level(bridges = setOf(p(1, 1)))
        assertEquals(emptyMap(), BoardEdits.owners(bridged, emptyMap()))
        val a = listOf(p(0, 1), p(1, 1), p(2, 1))
        val b = listOf(p(1, 0), p(1, 1), p(1, 2))
        // Prior owner no longer crosses: the non-newest crosser owns the bridge.
        val owned = BoardEdits.owners(bridged, mapOf(0 to a, 1 to b), prior = mapOf(p(1, 1) to 7), newest = 1)
        assertEquals(0, owned[p(1, 1)])
        // Only the newest crosses: it owns the bridge by default.
        val solo = BoardEdits.owners(bridged, mapOf(1 to b), newest = 1)
        assertEquals(1, solo[p(1, 1)])
        // Non-bridge cells belong to the line that covers them.
        assertEquals(0, owned[p(0, 1)])
        assertEquals(1, owned[p(1, 0)])
    }

    @Test fun progressCodecRejectsBrokenHistoryAndVersions() {
        val lvl = level()
        assertNull(ProgressCodec.decode("""{"s":{"p":{},"o":{}},"h":[]}""", lvl))
        assertNull(ProgressCodec.decode("""{"v":1}""", lvl))
        assertNull(ProgressCodec.decode("""{"v":1,"s":{"p":{},"o":{}},"h":[{"p":{"9":[0,0,0,1]},"o":{}}]}""", lvl))
        assertNull(ProgressCodec.decode("""[1,2]""", lvl))
        // An owner entry for a cell covered by a single line is not written back out.
        val state = BoardState(mapOf(0 to listOf(p(0, 0), p(0, 1))), mapOf(p(0, 0) to 0, p(0, 1) to 0))
        val encoded = ProgressCodec.encode(SavedProgress(state, emptyList()))
        assertTrue("\"o\":{}" in encoded, encoded)
        val decoded = ProgressCodec.decode(encoded, lvl)
        assertEquals(state.paths, decoded!!.state.paths)
        // Missing owners object decodes with owners derived from the lines.
        val noOwners = """{"v":1,"s":{"p":{"0":[0,0,0,1]}},"h":[]}"""
        assertEquals(mapOf(p(0, 0) to 0, p(0, 1) to 0), ProgressCodec.decode(noOwners, lvl)!!.state.cellOwner)
    }

    @Test fun dailyPoolFilteringAndDeterminism() {
        assertEquals("daily_123_4", DailyLevels.levelId(123, 4))
        val classic = (0 until 6).map { i ->
            val cells = rect(2 + i, 2)
            LevelData(
                "c$i", 2 + i, 2, cells, computeAdjacency(cells), null,
                listOf(EndpointPair(0, listOf(p(0, 0), p(0, 1)))) , emptySet(), 1,
            )
        }
        val custom = level(renderPositions = mapOf(p(0, 0) to RenderPos(0f, 0f))).copy(id = "custom")
        assertNull(DailyLevels.packFor(10, classic.take(4) + custom))
        val pack = DailyLevels.packFor(10, classic + custom)!!
        assertEquals(5, pack.levels.size)
        assertTrue(pack.levels.none { it.id == "custom" })
        assertEquals(pack.levels.map { it.cells.size }, pack.levels.map { it.cells.size }.sorted())
        assertEquals((0 until 5).map { DailyLevels.levelId(10, it) }, pack.levels.map { it.id })
        assertEquals(pack, DailyLevels.packFor(10, classic + custom))
        assertTrue(DailyLevels.packFor(11, classic + custom)!!.levels.all { it.id.startsWith("daily_11_") })
        assertEquals("daily_10", pack.name)
        assertEquals("square", pack.shape)
    }

    @Test fun generatorEdgeCases() {
        assertEquals(emptySet(), LevelGenerator.rectangularCells(0, 3))
        assertEquals(setOf(p(0, 0), p(0, 1), p(1, 0), p(1, 1)), LevelGenerator.rectangularCells(2, 2))
        assertEquals(4, LevelGenerator.flowCeiling(rect(2, 2)))
        assertEquals(5, LevelGenerator.flowCeiling(rect(5, 5)))
        // No adjacency at all: no carve can start, so no level.
        assertNull(LevelGenerator.generateLevel(rect(3, 3), emptyMap(), 4, seed = 7, id = "none"))
        // A pack over an impossible board is empty; the id scheme is still exercised via a real board.
        assertEquals(emptyList(), LevelGenerator.generatePack("A B×C", rect(3, 3), emptyMap(), 3, 4, seed = 1))
        val cells = rect(5, 5)
        val pack = LevelGenerator.generatePack("My Pack×1", cells, computeAdjacency(cells), 2, 5, seed = 42)
        assertTrue(pack.all { it.id.startsWith("My_Packx1_") }, pack.map { it.id }.toString())
        assertTrue(pack.all { it.endpoints.isNotEmpty() })
    }

    @Test fun packJsonExplicitAdjacencyAndRenderPositions() {
        val json = """
            {"name":"Explicit","shape":"square","levels":[{
              "id":"e1","rows":2,"cols":2,
              "cells":[[0,0],[0,1],[1,0],[1,1]],
              "adjacency":{"0,0":[[0,1],[1,0]],"0,1":[[0,0]],"1,0":[[0,0]],"1,1":[]},
              "renderPositions":{"0,0":{"x":0.5,"y":0.25},"1,1":{"x":2.5,"y":3.5}},
              "portals":[[[0,0],[1,1]]],
              "extraEdges":[[[0,1],[1,0]],[[1,1]]],
              "bridges":[[1,0]],
              "endpoints":[{"color":7,"cells":[[0,0],[1,1]]},{"cells":[[0,1],[1,0]]}],
              "optimalMoves":2
            },{
              "id":"h1","rows":2,"cols":2,"grid":"hex",
              "endpoints":[{"cells":[[0,0],[1,1]]}],"optimalMoves":1
            }]}
        """.trimIndent()
        val method = Class.forName("com.vayunmathur.games.pipes.data.PipeModelsKt")
            .getDeclaredMethod("packFromJson", String::class.java)
        method.isAccessible = true
        val pack = method.invoke(null, json) as LevelPack
        assertEquals("Explicit", pack.name)
        val first = pack.levels[0]
        // Explicit adjacency is honoured and unioned with the portal + extra edge.
        assertTrue(p(1, 1) in first.adjacency.getValue(p(0, 0)))
        assertTrue(p(1, 0) in first.adjacency.getValue(p(0, 1)))
        assertEquals(RenderPos(0.5f, 0.25f), first.renderPositions!![p(0, 0)])
        assertEquals(RenderPos(2.5f, 3.5f), first.renderPositions[p(1, 1)])
        assertEquals(setOf(p(1, 0)), first.bridges)
        assertEquals(7, first.endpoints[0].colorIndex)
        assertEquals(1, first.endpoints[1].colorIndex)
        assertEquals(mapOf(p(0, 0) to p(1, 1), p(1, 1) to p(0, 0)), first.portals)
        // Hex level with no declared adjacency/render positions derives both.
        val hex = pack.levels[1]
        assertEquals("hex", hex.grid)
        assertTrue(hex.renderPositions!!.isNotEmpty())
        assertTrue(hex.adjacency.getValue(p(0, 0)).isNotEmpty())
    }
}
