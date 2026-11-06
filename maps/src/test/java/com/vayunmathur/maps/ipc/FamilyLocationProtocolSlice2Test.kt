package com.vayunmathur.maps.ipc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The findfamily wire contract mirror and the decoded member model.
 * Slice-2 coverage for maps (basename F, outside the slice-1 dirs).
 */
class FamilyLocationProtocolSlice2Test {

    @Test
    fun `the wire constants match the documented contract`() {
        assertEquals("com.vayunmathur.findfamily.action.FAMILY_LOCATIONS", FamilyLocationProtocol.ACTION)
        assertEquals("com.vayunmathur.findfamily.permissions.ACCESS_FAMILY", FamilyLocationProtocol.PERMISSION)
        assertEquals(1, FamilyLocationProtocol.MSG_REGISTER)
        assertEquals(2, FamilyLocationProtocol.MSG_UNREGISTER)
        assertEquals(3, FamilyLocationProtocol.MSG_LOCATIONS)
        assertEquals("ids", FamilyLocationProtocol.KEY_IDS)
        assertEquals("names", FamilyLocationProtocol.KEY_NAMES)
        assertEquals("lats", FamilyLocationProtocol.KEY_LATS)
        assertEquals("lngs", FamilyLocationProtocol.KEY_LNGS)
        assertEquals("timestamps", FamilyLocationProtocol.KEY_TIMESTAMPS)
        assertEquals("batteries", FamilyLocationProtocol.KEY_BATTERIES)
    }

    @Test
    fun `message codes are distinct`() {
        val codes = setOf(
            FamilyLocationProtocol.MSG_REGISTER,
            FamilyLocationProtocol.MSG_UNREGISTER,
            FamilyLocationProtocol.MSG_LOCATIONS,
        )
        assertEquals(3, codes.size)
    }

    @Test
    fun `a member holds its snapshot fields`() {
        val member = FamilyMember(
            id = 7L,
            name = "Ada",
            lat = 37.8,
            lng = -122.4,
            timestamp = 1_700_000_000_000L,
            battery = 0.82f,
        )
        assertEquals(7L, member.id)
        assertEquals("Ada", member.name)
        assertEquals(37.8, member.lat)
        assertEquals(-122.4, member.lng)
        assertEquals(1_700_000_000_000L, member.timestamp)
        assertEquals(0.82f, member.battery)
    }

    @Test
    fun `members compare by value`() {
        val a = FamilyMember(1L, "Ada", 1.0, 2.0, 3L, -1f)
        assertEquals(a, a.copy())
        assertTrue(a != a.copy(name = "Bo"))
    }
}
