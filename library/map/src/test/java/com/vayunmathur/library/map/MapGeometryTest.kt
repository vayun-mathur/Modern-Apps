package com.vayunmathur.library.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapGeometryTest {

    private val all = MapGeometryKind.entries.toSet()

    @Test
    fun parsesFeatureRowsWithClassesAndClosure() {
        val rows = arrayOf(
            "OK\u00010\u00013\u00014",
            "ROAD\u0001residential\u00010\u0001-1224194000,377749000,-1224193000,377749100",
            "WATER\u0001lake\u00011\u00011000000,510000000,1001000,510000000,1001000,510001000",
            "BUILDING\u0001building\u00011\u00011000000,510000000,1001000,510000000,1001000,510001000",
        )
        val result = MapGeometry.parseRows(rows, all)
        assertTrue(result.unsourcedKinds.isEmpty())
        assertFalse(result.truncated)
        assertEquals(3, result.coveredTiles)
        assertEquals(4, result.totalTiles)
        assertEquals(3, result.features.size)
        val road = result.featuresOf(MapGeometryKind.ROAD).single()
        assertEquals("residential", road.kindClass)
        assertFalse(road.closed)
        assertTrue(kotlin.math.abs(road.points.first().longitude - -122.4194) < 1e-9)
        assertTrue(kotlin.math.abs(road.points.first().latitude - 37.7749) < 1e-9)
        assertTrue(result.featuresOf(MapGeometryKind.WATER).single().closed)
        assertTrue(result.featuresOf(MapGeometryKind.BUILDING).single().closed)
    }

    @Test
    fun unavailableMarksEveryRequestedKindUnsourced() {
        for (rows in listOf(arrayOf("UNAVAILABLE"), emptyArray(), arrayOf("GARBAGE"))) {
            val result = MapGeometry.parseRows(rows, all)
            assertEquals(all, result.unsourcedKinds)
            assertTrue(result.features.isEmpty())
        }
        // Only the requested kinds are blamed.
        val result = MapGeometry.parseRows(arrayOf("UNAVAILABLE"), setOf(MapGeometryKind.ROAD))
        assertEquals(setOf(MapGeometryKind.ROAD), result.unsourcedKinds)
    }

    @Test
    fun scannedEmptyRegionIsNotUnsourced() {
        // OK header, no feature rows: the archive was read and the
        // region genuinely holds none of the requested kinds.
        val result = MapGeometry.parseRows(arrayOf("OK\u00010\u00012\u00012"), all)
        assertTrue(result.unsourcedKinds.isEmpty())
        assertTrue(result.features.isEmpty())
        assertEquals(2, result.coveredTiles)
    }

    @Test
    fun truncatedHeaderAndMalformedRowsAreHonest() {
        val rows = arrayOf(
            "OK\u00011\u00011\u00011",
            "ROAD\u0001\u00010\u0001not,numbers",
            "PATH\u0001path\u00010\u00011000000,510000000,1000500,510000500",
            "WATERBROKENROW",
        )
        val result = MapGeometry.parseRows(rows, all)
        assertTrue(result.truncated)
        // The malformed road row is skipped; the valid path survives.
        assertEquals(listOf(MapGeometryKind.PATH), result.features.map { it.kind })
    }

    @Test
    fun noArchivePathIsUnsourcedWithoutTouchingNative() {
        // archivePath blank: query() must answer unsourced directly.
        // (On a host JVM the native library is absent too; either path
        // lands on the same honest answer.)
        val result = MapGeometry.query(
            archivePath = "",
            bounds = GeoBounds(-0.01, 51.5, 0.01, 51.51),
        )
        assertEquals(all, result.unsourcedKinds)
        assertTrue(result.features.isEmpty())
        val degenerate = MapGeometry.query(
            archivePath = "/nonexistent/basemap.mamaps",
            bounds = GeoBounds(1.0, 1.0, 1.0, 2.0),
        )
        assertEquals(all, degenerate.unsourcedKinds)
    }
}
