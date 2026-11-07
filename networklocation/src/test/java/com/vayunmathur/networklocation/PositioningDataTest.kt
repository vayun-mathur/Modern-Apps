package com.vayunmathur.networklocation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PositioningDataTest {

    @Test
    fun `radio wire values match the store protocol`() {
        assertEquals(1, RadioType.GSM.wire)
        assertEquals(2, RadioType.UMTS.wire)
        assertEquals(3, RadioType.LTE.wire)
        assertEquals(4, RadioType.NR.wire)
        assertEquals(
            listOf(RadioType.GSM, RadioType.UMTS, RadioType.LTE, RadioType.NR),
            RadioType.entries,
        )
        assertEquals(RadioType.LTE, RadioType.valueOf("LTE"))
    }

    @Test
    fun `beacon ids are value types`() {
        val wifi = BeaconId.Wifi("00:11:22:33:44:55")
        assertEquals(wifi, BeaconId.Wifi("00:11:22:33:44:55"))
        assertEquals(wifi.hashCode(), BeaconId.Wifi("00:11:22:33:44:55").hashCode())
        assertNotEquals(wifi, BeaconId.Wifi("00:11:22:33:44:56"))
        assertTrue(wifi.toString().contains("00:11:22:33:44:55"))

        val cell = BeaconId.Cell(310, 260, RadioType.NR, 0xFFFFFFFFFL, 0xFFFFFF)
        assertEquals(310, cell.mcc)
        assertEquals(260, cell.mnc)
        assertEquals(RadioType.NR, cell.radio)
        assertEquals(0xFFFFFFFFFL, cell.cellId, "36-bit NCI must not truncate")
        assertEquals(0xFFFFFF, cell.tacOrLac)
        assertEquals(cell, cell.copy())
        assertNotEquals(cell, cell.copy(radio = RadioType.LTE))
        // A wifi id never equals a cell id.
        assertNotEquals<BeaconId>(wifi, cell)
        val renamed = wifi.copy(bssid = "00:11:22:33:44:56")
        assertEquals("00:11:22:33:44:56", renamed.bssid)
        assertEquals("00:11:22:33:44:55", wifi.component1())
    }

    @Test
    fun `beacon fix defaults altitude to unknown`() {
        val id = BeaconId.Wifi("aa:bb:cc:dd:ee:ff")
        val fix = BeaconFix(id, 37.0, -122.0, 30.0)
        assertEquals(id, fix.id)
        assertEquals(37.0, fix.latitude)
        assertEquals(-122.0, fix.longitude)
        assertEquals(30.0, fix.accuracyMeters)
        assertNull(fix.altitudeMeters)
        val withAlt = fix.copy(altitudeMeters = 15.5)
        assertEquals(15.5, withAlt.altitudeMeters)
        assertNotEquals(fix, withAlt)
    }

    @Test
    fun `device position is a value type`() {
        val pos = DevicePosition(37.4215, -122.084, 12.0)
        assertEquals(pos, DevicePosition(37.4215, -122.084, 12.0))
        assertEquals(37.4215, pos.latitude)
        assertEquals(-122.084, pos.longitude)
        assertEquals(12.0, pos.accuracyMeters)
        assertNotEquals(pos, pos.copy(accuracyMeters = 13.0))
    }

    @Test
    fun `routes are distinct nav keys`() {
        assertNotEquals<Route>(Route.Setup, Route.Status)
        assertEquals(Route.Setup, Route.Setup)
        assertEquals(Route.Status, Route.Status)
    }

    @Test
    fun `routes serialize through their generated serializer`() {
        val json = kotlinx.serialization.json.Json
        val setup = json.encodeToString(Route.serializer(), Route.Setup)
        val status = json.encodeToString(Route.serializer(), Route.Status)
        assertNotEquals(setup, status)
        assertEquals(Route.Setup, json.decodeFromString(Route.serializer(), setup))
        assertEquals(Route.Status, json.decodeFromString(Route.serializer(), status))
    }
}
