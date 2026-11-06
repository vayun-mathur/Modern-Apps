package com.vayunmathur.maps.ui.map

import com.vayunmathur.library.map.MarkerIcon
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 1/3 (maps): [gtfsModeToMarkerIcon] — the coarse GTFS mode label folds
 * onto one of the four vehicle sprites the shared atlas draws.
 */
class TransitVehiclesSlice1Test {

    @Test
    fun `rail-like modes share the train sprite`() {
        for (mode in listOf("SUBWAY", "RAIL", "FUNICULAR", "AERIAL")) {
            assertEquals(MarkerIcon.VEHICLE_TRAIN, gtfsModeToMarkerIcon(mode), mode)
        }
    }

    @Test
    fun `street-level rail shares the tram sprite`() {
        assertEquals(MarkerIcon.VEHICLE_TRAM, gtfsModeToMarkerIcon("TRAM"))
        assertEquals(MarkerIcon.VEHICLE_TRAM, gtfsModeToMarkerIcon("MONORAIL"))
    }

    @Test
    fun `ferries get the ferry sprite and everything else the bus`() {
        assertEquals(MarkerIcon.VEHICLE_FERRY, gtfsModeToMarkerIcon("FERRY"))
        assertEquals(MarkerIcon.VEHICLE_BUS, gtfsModeToMarkerIcon("BUS"))
        assertEquals(MarkerIcon.VEHICLE_BUS, gtfsModeToMarkerIcon("TROLLEYBUS"))
        assertEquals(MarkerIcon.VEHICLE_BUS, gtfsModeToMarkerIcon("COACH"))
        assertEquals(MarkerIcon.VEHICLE_BUS, gtfsModeToMarkerIcon("SOMETHING_NEW"))
    }
}
