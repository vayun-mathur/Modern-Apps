package com.vayunmathur.maps.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Slice 1/3 (maps): [roadShieldFrom] — the ref parser behind the maneuver
 * banner's shield badge (Decision D10: no dedicated ref field in the graph,
 * so the instruction text is parsed).
 */
class RoadShieldSlice1Test {

    @Test
    fun `interstates parse with a dash`() {
        assertEquals(RoadShield("I-280", ShieldKind.INTERSTATE), roadShieldFrom("Continue on I-280"))
        assertEquals(RoadShield("I-5", ShieldKind.INTERSTATE), roadShieldFrom("Take I 5 north"))
    }

    @Test
    fun `us highways parse case-insensitively`() {
        assertEquals(RoadShield("US 101", ShieldKind.US_HIGHWAY), roadShieldFrom("Turn right onto US 101"))
        assertEquals(RoadShield("US 101", ShieldKind.US_HIGHWAY), roadShieldFrom("us-101"))
    }

    @Test
    fun `state routes parse but a bare US never reads as a state`() {
        assertEquals(RoadShield("CA-85", ShieldKind.STATE), roadShieldFrom("Merge onto CA-85"))
        assertEquals(RoadShield("NY-17", ShieldKind.STATE), roadShieldFrom("NY 17"))
        assertEquals(RoadShield("SR-237", ShieldKind.STATE), roadShieldFrom("SR-237"))
        // "US 101" must not ALSO match the two-letter state pattern as "US-101".
        assertEquals(ShieldKind.US_HIGHWAY, roadShieldFrom("US 101")?.kind)
    }

    @Test
    fun `european refs parse`() {
        assertEquals(RoadShield("A4", ShieldKind.GENERIC), roadShieldFrom("Take the A4 towards Paris"))
        assertEquals(RoadShield("M1", ShieldKind.GENERIC), roadShieldFrom("M1"))
        assertEquals(RoadShield("B500", ShieldKind.GENERIC), roadShieldFrom("B500"))
    }

    @Test
    fun `interstate wins over a state ref in the same string`() {
        // Priority order is a product decision, pinned: the first match class wins.
        assertEquals(ShieldKind.INTERSTATE, roadShieldFrom("I-280 to CA-85")?.kind)
    }

    @Test
    fun `named streets and blanks have no shield`() {
        assertNull(roadShieldFrom("Turn left onto Market Street"))
        assertNull(roadShieldFrom(""))
        assertNull(roadShieldFrom("   "))
        assertNull(roadShieldFrom(null))
        assertNull(roadShieldFrom("A"))
    }
}
