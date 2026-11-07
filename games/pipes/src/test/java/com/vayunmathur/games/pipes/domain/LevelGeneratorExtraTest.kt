package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import com.vayunmathur.games.pipes.data.computeHexAdjacency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LevelGeneratorExtraTest {

    @Test fun rectangularCellsAndFlowCeiling() {
        assertEquals(1, LevelGenerator.rectangularCells(1, 1).size)
        assertEquals(setOf(CellPos(0, 0), CellPos(0, 1), CellPos(1, 0), CellPos(1, 1)), LevelGenerator.rectangularCells(2, 2))
        assertTrue(LevelGenerator.rectangularCells(0, 5).isEmpty())
        // floor of 4, then roughly one flow per five cells
        assertEquals(4, LevelGenerator.flowCeiling(LevelGenerator.rectangularCells(3, 3)))
        assertEquals(5, LevelGenerator.flowCeiling(LevelGenerator.rectangularCells(5, 5)))
        assertEquals(20, LevelGenerator.flowCeiling(LevelGenerator.rectangularCells(10, 10)))
    }

    @Test fun generatePackProducesNumberedLevels() {
        val cells = LevelGenerator.rectangularCells(5, 5)
        var pack = emptyList<com.vayunmathur.games.pipes.data.LevelData>()
        for (base in listOf(4_000L, 12_345L, 777L, 99_991L, 5_555L)) {
            pack = LevelGenerator.generatePack(
                name = "Test×Pack A", cells = cells, adjacency = computeAdjacency(cells),
                levelCount = 2, maxFlows = LevelGenerator.flowCeiling(cells), seed = base,
            )
            if (pack.isNotEmpty()) break
        }
        assertTrue(pack.isNotEmpty(), "pack was empty for every seed")
        assertTrue(pack.all { it.id.startsWith("TestxPack_A_") }, pack.map { it.id }.toString())
        assertTrue(pack.all { it.endpoints.size == it.optimalMoves })
        assertTrue(pack.all { lvl -> lvl.endpoints.flatMap { it.cells }.all { it in cells } })
    }

    @Test fun generateLevelOnHexTopologySkipsSquareUniquenessGate() {
        val cells = LevelGenerator.rectangularCells(4, 4)
        val level = LevelGenerator.generateLevel(
            cells = cells, adjacency = computeHexAdjacency(cells),
            maxFlows = 6, seed = 123L, id = "hex_gen", earlyStopProb = 0.3f, grid = "hex",
        )
        assertNotNull(level)
        assertEquals("hex", level.grid)
        assertEquals("hex_gen", level.id)
        assertEquals(cells, level.cells)
        val endpointCells = level.endpoints.flatMap { it.cells }
        assertEquals(endpointCells.size, endpointCells.toSet().size)
    }

    @Test fun generateLevelWithBridgesAndPortalsCarriesMetadata() {
        val cells = LevelGenerator.rectangularCells(5, 5)
        val bridge = CellPos(2, 2)
        val portals = mapOf(CellPos(0, 0) to CellPos(4, 4), CellPos(4, 4) to CellPos(0, 0))
        var produced = 0
        for (seed in 0 until 6) {
            val level = LevelGenerator.generateLevel(
                cells = cells, adjacency = computeAdjacency(cells),
                maxFlows = LevelGenerator.flowCeiling(cells), seed = 4_000L + seed * 131L,
                id = "meta_$seed", bridges = setOf(bridge), portals = portals,
            ) ?: continue
            produced++
            assertEquals(setOf(bridge), level.bridges)
            assertEquals(portals, level.portals)
        }
        assertTrue(produced > 0)
    }
}
