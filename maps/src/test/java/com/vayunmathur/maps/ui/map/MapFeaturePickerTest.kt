package com.vayunmathur.maps.ui.map
import com.vayunmathur.maps.data.regionId

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.vayunmathur.maps.data.Feature1
import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.ui.FAMILY_LOCATION_LAYER_ID
import com.vayunmathur.maps.ui.PARKING_PIN_LAYER_ID
import com.vayunmathur.maps.ui.SAVED_PLACE_LAYER_ID
import com.vayunmathur.maps.ui.SEARCH_RESULT_LAYER_ID
import com.vayunmathur.maps.ui.TRANSIT_STOP_LAYER_ID
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.NATIVE_LABEL_LAYER_IDS
import com.vayunmathur.maps.ui.map.MapFeaturePicker.Companion.toFeature1
import com.vayunmathur.maps.ui.theme.MapChromeMetrics
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.map.PlacedLabel
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import com.vayunmathur.maps.data.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hit-test priority, which used to be a hundred-line lambda inside a gesture handler and so
 * could not be tested at all. It is priority order that matters here: a parking pin and a POI
 * can sit on the same point, and which one a tap selects is a product decision, not an
 * accident of which `?:` came first.
 *
 * [FeatureSource] is the seam — no renderer, no composition, no device.
 *
 * [MapFeaturePicker.pickPin] suspends because the POI probe reads a sidecar file, so the tests
 * that call it run under [runBlocking]. Nothing here actually blocks: [FakeSource] answers from
 * a map, and no probe reaches real I/O.
 */
class MapFeaturePickerTest {

    /** Records what was asked for, and answers with whatever the test planted. */
    private class FakeSource(private val hits: Map<String, List<Feature1>>) : FeatureSource {
        val queried = mutableListOf<Set<String>>()
        val boxes = mutableListOf<DpRect>()

        override fun query(box: DpRect, layerIds: Set<String>): List<Feature1> {
            queried += layerIds
            boxes += box
            return layerIds.flatMap { hits[it] ?: emptyList() }
        }
    }

    /** A geometry-only feature: present on the layer, but with no properties to resolve from. */
    private fun bareFeature() = Feature1(Point(GeoPoint(0.0, 0.0)), null)

    private val tap = DpOffset(100.dp, 200.dp)

    @Test
    fun `probes run parking, stop, search, saved, family in that order`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        assertEquals(
            listOf(
                setOf(PARKING_PIN_LAYER_ID),
                setOf(TRANSIT_STOP_LAYER_ID),
                setOf(SEARCH_RESULT_LAYER_ID),
                setOf(SAVED_PLACE_LAYER_ID),
                setOf(FAMILY_LOCATION_LAYER_ID),
            ),
            source.queried,
        )
    }

    /**
     * Ambient POIs left this chain when the renderer took over drawing them: they arrive as
     * `MapClick.poi`, already picked against the collision pass that put them on screen.
     * Pinned so a future change cannot quietly reintroduce the duplicate viewport query.
     */
    @Test
    fun `poi layers are not probed here`() = runBlocking {
        val source = FakeSource(emptyMap())
        val picker = MapFeaturePicker(source, transitEnabled = true)
        picker.pickPin(tap)
        assertTrue(
            picker.probeOrder.none { it.contains("poi", ignoreCase = true) },
            "a POI probe came back: ${picker.probeOrder}",
        )
    }

    @Test
    fun `probeOrder matches what actually gets queried`() = runBlocking {
        val source = FakeSource(emptyMap())
        val picker = MapFeaturePicker(source, transitEnabled = true)
        picker.pickPin(tap)
        assertEquals(picker.probeOrder, source.queried.map { it.single() })
    }

    /** Parking wins outright: a car spot on top of a family pin is still the car spot. */
    @Test
    fun `parking beats everything below it and stops the search`() = runBlocking {
        val source = FakeSource(
            mapOf(
                PARKING_PIN_LAYER_ID to listOf(bareFeature()),
                FAMILY_LOCATION_LAYER_ID to listOf(bareFeature()),
            )
        )
        val hit = MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        assertEquals(MapHit.Parking, hit)
        assertEquals(1, source.queried.size, "later probes must not run once a hit is found")
    }

    /** The transit probe is skipped entirely when the layer is not drawn. */
    @Test
    fun `a transit stop is not probed while the layer is off`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = false).pickPin(tap)
        assertTrue(
            source.queried.none { TRANSIT_STOP_LAYER_ID in it },
            "probed an invisible layer: ${source.queried}",
        )
    }

    @Test
    fun `probeOrder omits the transit layer while it is off`() {
        val picker = MapFeaturePicker(FakeSource(emptyMap()), transitEnabled = false)
        assertTrue(TRANSIT_STOP_LAYER_ID !in picker.probeOrder)
        assertEquals(4, picker.probeOrder.size)
    }

    /**
     * A feature that is present but does not resolve must not stop the search — the layer
     * being hit and the hit being usable are different things.
     */
    @Test
    fun `an unresolvable feature falls through to the next probe`() = runBlocking {
        val source = FakeSource(mapOf(SEARCH_RESULT_LAYER_ID to listOf(bareFeature())))
        val hit = MapFeaturePicker(source, transitEnabled = false).pickPin(tap)

        assertNull(hit)
        assertTrue(
            source.queried.any { FAMILY_LOCATION_LAYER_ID in it },
            "gave up before reaching the last probe",
        )
    }

    @Test
    fun `no hits anywhere yields null`() = runBlocking {
        assertNull(MapFeaturePicker(FakeSource(emptyMap()), transitEnabled = true).pickPin(tap))
    }

    /** The slop box is what makes tapping *near* a small glyph work. */
    @Test
    fun `every pin probe uses the same hit slop box around the tap`() = runBlocking {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickPin(tap)

        val pad = MapChromeMetrics.hitSlop
        val expected = DpRect(tap.x - pad, tap.y - pad, tap.x + pad, tap.y + pad)
        assertTrue(source.boxes.isNotEmpty())
        for (box in source.boxes) assertEquals(expected, box)
    }

    /** Labels are probed at the point, with no slop — matching the pre-refactor behaviour. */
    @Test
    fun `admin labels are probed at the exact point`() {
        val source = FakeSource(emptyMap())
        MapFeaturePicker(source, transitEnabled = true).pickAdminLabels(tap)

        assertEquals(1, source.boxes.size)
        assertEquals(DpRect(tap.x, tap.y, tap.x, tap.y), source.boxes.single())
        assertEquals(MapFeaturePicker.ADMIN_LABEL_LAYER_IDS, source.queried.single())
    }

    /** The style patcher splits every base layer into `_base` and `_hybrid` by zoom. */
    @Test
    fun `admin label ids cover both zoom variants of all three levels`() {
        val ids = MapFeaturePicker.ADMIN_LABEL_LAYER_IDS
        assertEquals(6, ids.size)
        for (level in listOf("places_country", "places_region", "places_locality")) {
            assertTrue("${level}_base" in ids, "missing ${level}_base")
            assertTrue("${level}_hybrid" in ids, "missing ${level}_hybrid")
        }
    }

    /** The native pick emits flat dash ids; the query must use those, not the split ones. */
    @Test
    fun `native label ids are the flat renderer ids`() {
        assertEquals(
            setOf("places-country", "places-region", "places-locality", "places-subplace"),
            NATIVE_LABEL_LAYER_IDS,
        )
    }

    /** The adapter emits the {kind, name, name:en, regionId} properties `parse` reads. */
    @Test
    fun `placed label converts to a parseable feature`() {
        val feature = PlacedLabel(
            layerId = "places-country",
            name = "France",
            kind = "country",
            position = GeoPoint(2.35, 48.85),
            regionId = 12345L,
        ).toFeature1() ?: error("expected a feature")

        val props = feature.properties ?: error("expected properties")
        assertEquals("country", props["kind"]?.jsonPrimitive?.content)
        assertEquals("France", props["name"]?.jsonPrimitive?.content)
        assertEquals("France", props["name:en"]?.jsonPrimitive?.content)
        assertEquals("12345", props["regionId"]?.jsonPrimitive?.content)
        val pos = (feature.geometry as? Point)?.coordinates ?: error("expected point")
        assertEquals(2.35, pos.longitude)
        assertEquals(48.85, pos.latitude)
    }

    /** A linked subplace converts with locality semantics so it can mask. */
    @Test
    fun `linked subplace converts as locality with its region id`() {
        val feature = PlacedLabel(
            layerId = "places-subplace",
            name = "SoHo",
            kind = "subplace",
            position = GeoPoint(-74.0, 40.7),
            regionId = 999L,
        ).toFeature1() ?: error("expected a feature")

        val props = feature.properties ?: error("expected properties")
        assertEquals("locality", props["kind"]?.jsonPrimitive?.content)
        assertEquals("999", props["regionId"]?.jsonPrimitive?.content)
    }

    /** Unknown native layers convert to null and never enter the candidate list. */
    @Test
    fun `unmapped native layers convert to null`() {
        assertNull(
            PlacedLabel("roads-bridges-major-casing", "x", "road", GeoPoint(0.0, 0.0)).toFeature1()
        )
    }

    /** The mask is the label's baked id: nonzero passes through, zero/null clears. */
    @Test
    fun `region mask passes the baked id through`() {
        val paris = GeoPoint(2.35, 48.85)
        assertEquals(
            777L,
            regionMaskFor(SpecificFeature.Admin2Label(name = "Paris", position = paris, regionId = 777L)),
        )
        assertEquals(
            42L,
            regionMaskFor(SpecificFeature.Admin0Label(name = "France", position = paris, regionId = 42L)),
        )
        assertEquals(
            43L,
            regionMaskFor(SpecificFeature.Admin1Label(name = "Île-de-France", position = paris, regionId = 43L)),
        )
        // Unlinked (0) or positionless labels, and non-admin features, mask nothing.
        assertNull(
            regionMaskFor(SpecificFeature.Admin2Label(name = "Nowhere", position = paris, regionId = 0L)),
        )
        assertNull(
            regionMaskFor(SpecificFeature.Admin2Label(name = "Nowhere", position = null, regionId = 777L)),
        )
        assertNull(regionMaskFor(null))
    }
}
