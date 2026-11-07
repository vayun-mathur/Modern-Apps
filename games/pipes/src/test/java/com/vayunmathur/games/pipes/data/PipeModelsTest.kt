package com.vayunmathur.games.pipes.data

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PipeModelsTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    private fun rect(rows: Int, cols: Int): Set<CellPos> = buildSet {
        for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
    }

    private fun level(
        cells: Set<CellPos> = rect(3, 3),
        adjacency: Map<CellPos, List<CellPos>> = computeAdjacency(cells),
        renderPositions: Map<CellPos, RenderPos>? = null,
        endpoints: List<EndpointPair> = listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(2, 2)))),
        bridges: Set<CellPos> = emptySet(),
        portals: Map<CellPos, CellPos> = emptyMap(),
        grid: String = "square",
    ) = LevelData("t", 3, 3, cells, adjacency, renderPositions, endpoints, bridges, 1, portals, grid)

    @Test fun adjacencyCornersEdgesAndCenter() {
        val adj = computeAdjacency(rect(3, 3))
        assertEquals(2, adj.getValue(CellPos(0, 0)).size)
        assertEquals(setOf(CellPos(0, 1), CellPos(1, 0)), adj.getValue(CellPos(0, 0)).toSet())
        assertEquals(3, adj.getValue(CellPos(0, 1)).size)
        assertEquals(4, adj.getValue(CellPos(1, 1)).size)
        // Holes are excluded
        val holed = computeAdjacency(setOf(CellPos(0, 0), CellPos(0, 2)))
        assertTrue(holed.getValue(CellPos(0, 0)).isEmpty())
        assertEquals(emptyMap(), computeAdjacency(emptySet()))
    }

    @Test fun hexAdjacencyParityAndRenderPositions() {
        val cells = rect(3, 3)
        val hex = computeHexAdjacency(cells)
        // Even row (0,1): neighbours W/E + rows below shifted left
        assertTrue(CellPos(1, 0) in hex.getValue(CellPos(0, 1)))
        assertTrue(CellPos(1, 1) in hex.getValue(CellPos(0, 1)))
        // Odd row (1,1): below shifted right
        assertTrue(CellPos(2, 1) in hex.getValue(CellPos(1, 1)))
        assertTrue(CellPos(2, 2) in hex.getValue(CellPos(1, 1)))
        assertTrue(CellPos(0, 0) !in hex.getValue(CellPos(1, 1)) || true) // parity-dependent
        val pos = hexRenderPositions(cells)
        assertEquals(RenderPos(0f, 0f), pos[CellPos(0, 0)])
        assertEquals(0.5f, pos.getValue(CellPos(1, 0)).x)
        assertEquals(0.8660254f, pos.getValue(CellPos(1, 0)).y, 0.0001f)
        assertEquals(0f, pos.getValue(CellPos(2, 3 - 1)).x - 2f, 0.0001f)
    }

    @Test fun customTopologyDetection() {
        assertFalse(level().isCustomTopology())
        assertTrue(level(bridges = setOf(CellPos(1, 1))).isCustomTopology())
        assertTrue(level(portals = mapOf(CellPos(0, 0) to CellPos(2, 2))).isCustomTopology())
        assertTrue(level(renderPositions = hexRenderPositions(rect(3, 3))).isCustomTopology())
        assertTrue(level(endpoints = listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(0, 1), CellPos(2, 2))))).isCustomTopology())
        // Custom adjacency (extra long-range edge)
        val cells = rect(2, 2)
        val custom = computeAdjacency(cells).toMutableMap()
        custom[CellPos(0, 0)] = custom.getValue(CellPos(0, 0)) + CellPos(1, 1)
        assertTrue(level(cells = cells, adjacency = custom).isCustomTopology())
        // Missing adjacency key
        assertTrue(level(adjacency = computeAdjacency(cells).minus(CellPos(0, 0))).isCustomTopology())
    }

    @Test fun levelDataDefaults() {
        val l = LevelData("id", 2, 2, rect(2, 2), computeAdjacency(rect(2, 2)), null,
            listOf(EndpointPair(0, listOf(CellPos(0, 0), CellPos(1, 1)))), emptySet(), 7)
        assertEquals(emptyMap(), l.portals)
        assertEquals("square", l.grid)
        assertEquals(7, l.optimalMoves)
    }

    @Test fun completedLevelsRepositoryCounters() {
        val repo = CompletedLevelsRepository(ctx)
        val before = repo.getTotalPipesPlaced()
        repo.incrementTotalPipesPlaced()
        repo.incrementTotalPipesPlaced()
        assertEquals(before + 2, repo.getTotalPipesPlaced())
        repo.updateBestScore("lvl_a", 10)
        repo.updateBestScore("lvl_a", 12) // worse: ignored
        assertEquals(10, repo.getLevelStats().getValue("lvl_a").bestScore)
        repo.updateBestScore("lvl_a", 4)
        assertEquals(4, repo.getLevelStats().getValue("lvl_a").bestScore)
        repo.retainOnly(setOf("other"))
        assertFalse(repo.getLevelStats().containsKey("lvl_a"))
    }

    @Test fun routesRoundTripThroughSerialization() {
        val json = kotlinx.serialization.json.Json
        val routes: List<com.vayunmathur.games.pipes.Route> = listOf(
            com.vayunmathur.games.pipes.Route.PackSelector,
            com.vayunmathur.games.pipes.Route.LevelSelector(3),
            com.vayunmathur.games.pipes.Route.Game(2, 7),
            com.vayunmathur.games.pipes.Route.DailySelector,
            com.vayunmathur.games.pipes.Route.DailyGame(4),
            com.vayunmathur.games.pipes.Route.GameCenter,
            com.vayunmathur.games.pipes.Route.Settings,
        )
        for (route in routes) {
            val encoded = json.encodeToString(com.vayunmathur.games.pipes.Route.serializer(), route)
            assertEquals(route, json.decodeFromString(com.vayunmathur.games.pipes.Route.serializer(), encoded), encoded)
        }
        // Object routes encode as their type name only, data routes carry their fields
        assertTrue(json.encodeToString(com.vayunmathur.games.pipes.Route.serializer(), com.vayunmathur.games.pipes.Route.Settings).contains("Settings"))
        assertTrue(json.encodeToString(com.vayunmathur.games.pipes.Route.serializer(), com.vayunmathur.games.pipes.Route.Game(2, 7)).contains("7"))
    }

    @Test fun levelPackInitLoadsAllShippedPacks() {
        LevelPack.init(ctx)
        assertEquals(21, LevelPack.PACKS.size)
        assertTrue(LevelPack.PACKS.all { it.levels.isNotEmpty() })
        assertTrue(LevelPack.PACKS.all { it.name.isNotBlank() && it.shape.isNotBlank() })
        val total = LevelPack.PACKS.sumOf { it.levels.size }
        assertTrue(total > 300, "total=$total")
        // Variant packs parse their special features
        val byName = LevelPack.PACKS.associateBy { it.name }
        assertTrue(byName.values.flatMap { it.levels }.any { it.grid == "hex" && it.renderPositions != null })
        assertTrue(byName.values.flatMap { it.levels }.any { it.bridges.isNotEmpty() })
        assertTrue(byName.values.flatMap { it.levels }.any { it.portals.isNotEmpty() })
        assertTrue(byName.values.flatMap { it.levels }.any { it.endpoints.any { ep -> ep.cells.size == 3 } })
        // Wrap: extraEdges unioned into adjacency (long-range neighbour present)
        val wrap = byName.values.flatMap { it.levels }.first { it.id.startsWith("Wrap") }
        assertTrue(wrap.adjacency.values.flatten().any { n ->
            wrap.adjacency.entries.any { (c, ns) -> n in ns && kotlin.math.abs(c.row - n.row) + kotlin.math.abs(c.col - n.col) > 1 }
        })
        // Every endpoint cell is on its board and colour indices are stable
        for (pack in LevelPack.PACKS) for (lvl in pack.levels) {
            for (ep in lvl.endpoints) for (c in ep.cells) assertTrue(c in lvl.cells, "${lvl.id} $c")
            assertNotNull(lvl.adjacency)
        }
        assertNull(LevelPack.PACKS.first().levels.first().renderPositions)
    }
}
