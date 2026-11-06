package com.vayunmathur.maps.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 3/3 (root package files M-Z): the [PoiCategories] vocabulary that
 * joins the archive's `poi` layer kinds to the numeric search-index types.
 *
 * Pure Kotlin: labels for the result subtitle, the kind→type join, and the
 * departure-board routing predicate.
 */
class PoiCategoriesSlice3Test {

    @Test
    fun `labels cover the stable numbering`() {
        assertEquals("Restaurant", PoiCategories.label(0))
        assertEquals("Cafe", PoiCategories.label(1))
        assertEquals("Station", PoiCategories.label(50))
        assertEquals("Place of worship", PoiCategories.label(14))
    }

    @Test
    fun `unknown ids read as Place`() {
        assertEquals("Place", PoiCategories.label(51))
        assertEquals("Place", PoiCategories.label(255))
        assertEquals("Place", PoiCategories.label(-1))
    }

    @Test
    fun `food kinds map to their types`() {
        assertEquals(0, PoiCategories.typeOfKind("restaurant"))
        assertEquals(1, PoiCategories.typeOfKind("cafe"))
        assertEquals(2, PoiCategories.typeOfKind("fast_food"))
        assertEquals(3, PoiCategories.typeOfKind("bar"))
    }

    @Test
    fun `shop kinds map to their types`() {
        assertEquals(5, PoiCategories.typeOfKind("supermarket"))
        assertEquals(5, PoiCategories.typeOfKind("convenience"))
        assertEquals(6, PoiCategories.typeOfKind("fuel"))
        assertEquals(8, PoiCategories.typeOfKind("hotel"))
        assertEquals(9, PoiCategories.typeOfKind("bank"))
        assertEquals(9, PoiCategories.typeOfKind("atm"))
    }

    @Test
    fun `leisure and civic kinds map to their types`() {
        assertEquals(11, PoiCategories.typeOfKind("school"))
        assertEquals(11, PoiCategories.typeOfKind("university"))
        assertEquals(12, PoiCategories.typeOfKind("park"))
        assertEquals(15, PoiCategories.typeOfKind("zoo"))
        assertEquals(38, PoiCategories.typeOfKind("museum"))
        assertEquals(19, PoiCategories.typeOfKind("library"))
        assertEquals(50, PoiCategories.typeOfKind("station"))
    }

    @Test
    fun `kinds with no bucket return null instead of a wrong number`() {
        assertNull(PoiCategories.typeOfKind("beach"))
        assertNull(PoiCategories.typeOfKind("peak"))
        assertNull(PoiCategories.typeOfKind("bench"))
        assertNull(PoiCategories.typeOfKind("artwork"))
        assertNull(PoiCategories.typeOfKind("building"))
        assertNull(PoiCategories.typeOfKind(""))
        assertNull(PoiCategories.typeOfKind("pharmacy"))
    }

    @Test
    fun `station type constant matches the label table`() {
        assertEquals(50, PoiCategories.STATION_TYPE)
        assertEquals("Station", PoiCategories.label(PoiCategories.STATION_TYPE))
    }

    @Test
    fun `transit-stop kinds open the departure board`() {
        assertTrue(PoiCategories.opensDepartureBoard("station"))
        assertTrue(PoiCategories.opensDepartureBoard("bus_stop"))
        assertTrue(PoiCategories.opensDepartureBoard("ferry_terminal"))
    }

    @Test
    fun `ordinary places do not open the departure board`() {
        assertFalse(PoiCategories.opensDepartureBoard("restaurant"))
        assertFalse(PoiCategories.opensDepartureBoard("park"))
        assertFalse(PoiCategories.opensDepartureBoard(""))
    }
}
