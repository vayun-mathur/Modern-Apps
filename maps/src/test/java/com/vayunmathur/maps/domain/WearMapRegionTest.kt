package com.vayunmathur.maps.domain

import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.MapGeometryFeature
import com.vayunmathur.library.map.MapGeometryKind
import com.vayunmathur.library.map.MapGeometryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString

class WearMapRegionTest {

    private val center = GeoPoint(0.0, 0.0) // lon, lat — equator, metre math is exact-ish

    private fun east(meters: Double) = GeoPoint(meters / 111_320.0, 0.0)
    private fun north(meters: Double) = GeoPoint(0.0, meters / 110_540.0)

    @Test
    fun goldenSnapshotJson() {
        val snapshot = WearMapSnapshot(
            generatedAtMillis = 1_000L,
            centerLatE7 = 340_522_350,
            centerLonE7 = -1_182_436_830,
            lines = listOf(WearMapLine(WearLineKind.ROAD, listOf(0, 0, 10, 0))),
            points = listOf(WearMapPoint(0, "Cafe", 5, -3)),
        )
        assertEquals(
            """{"generatedAtMillis":1000,"centerLatE7":340522350,"centerLonE7":-1182436830,""" +
                """"lines":[{"kind":"ROAD","pts":[0,0,10,0]}],""" +
                """"points":[{"name":"Cafe","dx":5,"dy":-3}]}""",
            WearMapsProtocol.json.encodeToString(snapshot),
        )
    }

    @Test
    fun quantizationRoundTripWithinHalfStep() {
        for (meters in listOf(0.0, 1.0, 2.0, 3.0, 57.0, -123.0, 599.0)) {
            val back = wearDequantize(wearQuantize(meters))
            assertTrue(kotlin.math.abs(back - meters) <= WearMapsProtocol.QUANT_STEP_M / 2 + 1e-9, "$meters -> $back")
        }
    }

    @Test
    fun simplifyDropsCollinearKeepsCorner() {
        val line = listOf(east(0.0), east(50.0), east(100.0), north(0.0).let { GeoPoint(it.longitude, 100.0 / 110_540.0) })
        val simplified = simplifyWearLine(line, center, toleranceM = 4.0)
        assertEquals(3, simplified.size)
        assertEquals(line.first(), simplified.first())
        assertEquals(line.last(), simplified.last())
    }

    @Test
    fun extractionClipsToRegionAndCapsBytes() {
        // A road far outside must be clipped away; a long road inside is kept quantized.
        val outside = WearRawLine(WearLineKind.ROAD, listOf(east(5_000.0), east(6_000.0)))
        val inside = WearRawLine(
            WearLineKind.ROAD,
            (0..20).map { east(it * 50.0 - 500.0) },
        )
        val points = (1..200).map { WearRawPoint(0, "Poi $it with a reasonably long name", east(it * 3.0)) }
        val snapshot = buildWearMapSnapshot(center, 7L, listOf(outside, inside), points)
        val bytes = WearMapsProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        assertTrue(bytes <= WearMapsProtocol.MAX_JSON_BYTES, "snapshot $bytes over cap")
        assertTrue(snapshot.lines.isNotEmpty())
        assertTrue(snapshot.lines.all { line ->
            line.pts.indices.step(2).all { i ->
                kotlin.math.abs(wearDequantize(line.pts[i])) <= snapshot.radiusM + WearMapsProtocol.QUANT_STEP_M
            }
        })
        // Nearest points survive.
        assertTrue(snapshot.points.size <= 80)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun degradationDropsBuildingsBeforeRoads() {
        val fatName = "x".repeat(1_000)
        // All inside the 600 m region: horizontal roads + small building squares.
        val roads = (1..120).map { row ->
            WearRawLine(
                WearLineKind.ROAD,
                (0..30).map { i -> GeoPoint((i * 33.0 - 500.0) / 111_320.0, (row * 16.0 - 480.0) / 110_540.0) },
                name = fatName,
            )
        }
        val buildings = (1..120).map { b ->
            val bx = (b % 10) * 90.0 - 450.0
            val by = (b / 10) * 90.0 - 270.0
            WearRawLine(
                WearLineKind.BUILDING,
                listOf(
                    GeoPoint(bx / 111_320.0, by / 110_540.0),
                    GeoPoint((bx + 40.0) / 111_320.0, by / 110_540.0),
                    GeoPoint((bx + 40.0) / 111_320.0, (by + 40.0) / 110_540.0),
                    GeoPoint(bx / 111_320.0, (by + 40.0) / 110_540.0),
                ),
                closed = true, name = fatName,
            )
        }
        val snapshot = buildWearMapSnapshot(center, 0L, roads + buildings, emptyList())
        val bytes = WearMapsProtocol.json.encodeToString(snapshot).encodeToByteArray().size
        // Either it fits by dropping buildings, or it is honestly truncated at the final rung.
        assertTrue(snapshot.truncated)
        if (bytes <= WearMapsProtocol.MAX_JSON_BYTES) {
            assertTrue(snapshot.degraded.contains("buildings") || snapshot.degraded.contains("detail"))
        }
    }

    /** Real-shaped geometry as `MapGeometry` returns it for a city block at the equator. */
    private fun realShapedGeometry() = MapGeometryResult(
        features = listOf(
            MapGeometryFeature(
                MapGeometryKind.ROAD, "residential", closed = false,
                points = listOf(east(-400.0), east(-100.0), east(150.0), east(400.0)),
            ),
            MapGeometryFeature(
                MapGeometryKind.PATH, "path", closed = false,
                points = listOf(north(-300.0), north(300.0)),
            ),
            MapGeometryFeature(
                MapGeometryKind.WATER, "lake", closed = true,
                points = listOf(
                    GeoPoint(100.0 / 111_320.0, 100.0 / 110_540.0),
                    GeoPoint(300.0 / 111_320.0, 100.0 / 110_540.0),
                    GeoPoint(300.0 / 111_320.0, 250.0 / 110_540.0),
                    GeoPoint(100.0 / 111_320.0, 250.0 / 110_540.0),
                ),
            ),
            MapGeometryFeature(
                MapGeometryKind.BUILDING, "building", closed = true,
                points = listOf(
                    GeoPoint(-300.0 / 111_320.0, -200.0 / 110_540.0),
                    GeoPoint(-200.0 / 111_320.0, -200.0 / 110_540.0),
                    GeoPoint(-200.0 / 111_320.0, -100.0 / 110_540.0),
                    GeoPoint(-300.0 / 111_320.0, -100.0 / 110_540.0),
                ),
            ),
        ),
        coveredTiles = 4,
        totalTiles = 4,
    )

    @Test
    fun geometryResultPopulatesRoadsWaterAndBuildings() {
        val geometry = realShapedGeometry()
        val snapshot = buildWearMapSnapshot(
            center, 3L, geometry.toWearRawLines(), emptyList(),
            missingKinds = geometry.wearMissingKinds(),
            sourceTruncated = geometry.truncated,
        )
        assertEquals(emptyList(), snapshot.degraded, "a sourced region is not degraded")
        val kinds = snapshot.lines.map { it.kind }.toSet()
        assertTrue(WearLineKind.ROAD in kinds && WearLineKind.PATH in kinds)
        assertTrue(WearLineKind.WATER in kinds && WearLineKind.BUILDING in kinds)
        assertTrue(snapshot.lines.filter { it.closed }.all {
            it.kind == WearLineKind.WATER || it.kind == WearLineKind.BUILDING
        })
        // Quantized onto the 2 m grid and inside the region.
        assertTrue(snapshot.lines.all { line ->
            line.pts.indices.step(2).all { i ->
                kotlin.math.abs(wearDequantize(line.pts[i])) <= snapshot.radiusM + 2.0
            }
        })
    }

    @Test
    fun unavailableGeometryDegradesEveryBasemapKind() {
        val geometry = MapGeometryResult(
            unsourcedKinds = setOf(
                MapGeometryKind.ROAD, MapGeometryKind.PATH,
                MapGeometryKind.WATER, MapGeometryKind.BUILDING,
            ),
        )
        assertEquals(listOf("roads", "water", "buildings"), geometry.wearMissingKinds())
        val snapshot = buildWearMapSnapshot(
            center, 0L, geometry.toWearRawLines(), emptyList(),
            missingKinds = geometry.wearMissingKinds(),
        )
        assertEquals(listOf("roads", "water", "buildings"), snapshot.degraded)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test
    fun scannedButEmptyGeometryIsNotDegraded() {
        // The archive was read (tiles covered) and the region simply
        // holds no roads/water/buildings: a complete, empty map.
        val geometry = MapGeometryResult(coveredTiles = 2, totalTiles = 2)
        assertEquals(emptyList(), geometry.wearMissingKinds())
        val snapshot = buildWearMapSnapshot(
            center, 0L, geometry.toWearRawLines(), emptyList(),
            missingKinds = geometry.wearMissingKinds(),
        )
        assertEquals(emptyList(), snapshot.degraded)
    }

    @Test
    fun truncatedGeometryFlagsDetail() {
        val geometry = realShapedGeometry().copy(truncated = true)
        val snapshot = buildWearMapSnapshot(
            center, 0L, geometry.toWearRawLines(), emptyList(),
            missingKinds = geometry.wearMissingKinds(),
            sourceTruncated = geometry.truncated,
        )
        assertTrue(snapshot.truncated)
        assertTrue(snapshot.degraded.contains("detail"))
        assertTrue(snapshot.lines.isNotEmpty(), "capped geometry still draws what came back")
    }

    @Test
    fun missingKindsAreNamedHonestly() {
        val snapshot = buildWearMapSnapshot(
            center, 0L, emptyList(), emptyList(),
            missingKinds = listOf("roads", "water", "buildings"),
        )
        assertEquals(listOf("roads", "water", "buildings"), snapshot.degraded)
        assertTrue(snapshot.lines.isEmpty())
    }

    @Test
    fun hysteresisHoldsInsideAndFiresAtEdge() {
        assertFalse(WearRegionHysteresis.shouldRepublish(300.0, 600))
        assertFalse(WearRegionHysteresis.shouldRepublish(390.0, 600)) // exactly 65%
        assertTrue(WearRegionHysteresis.shouldRepublish(391.0, 600))
        assertTrue(WearRegionHysteresis.shouldRepublish(700.0, 600)) // outside
    }

    @Test
    fun routeStateCapsBytesAndKeepsManeuvers() {
        val polyline = (0..500).map { east(it * 10.0) }
        val maneuvers = listOf(WearManeuver("Turn left", "TURN_LEFT", 100))
        val state = buildWearRouteState(
            destinationName = "Home",
            polyline = polyline,
            maneuvers = maneuvers,
            nextText = "Turn left",
            distanceToNextM = 100,
            distanceRemainingM = 5_000,
            etaEpochMs = 123L,
            generatedAtMillis = 9L,
        )
        assertTrue(state.active)
        assertEquals(maneuvers, state.maneuvers)
        val bytes = WearMapsProtocol.json.encodeToString(state).encodeToByteArray().size
        assertTrue(bytes <= WearMapsProtocol.MAX_ROUTE_JSON_BYTES, "route $bytes over cap")
        // First route point is the quantized origin.
        assertEquals(listOf(0, 0), state.routePts.take(2))
    }

    @Test
    fun routeRoundTrip() {
        val state = WearRouteState(active = true, destinationName = "Work", nextText = "Straight")
        assertEquals(
            state,
            WearMapsProtocol.json.decodeFromString<WearRouteState>(
                WearMapsProtocol.json.encodeToString(state),
            ),
        )
        assertFalse(WearRouteState(active = false).active) // explicit clear shape
    }
}
