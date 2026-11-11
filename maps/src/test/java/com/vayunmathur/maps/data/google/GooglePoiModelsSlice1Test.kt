package com.vayunmathur.maps.data.google

import com.vayunmathur.maps.data.PoiPin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the [GooglePoiInfo] place-details model and the offline [PoiPin] —
 * in particular [GooglePoiInfo.isEmpty], the gate that decides whether the place sheet
 * shows a details section at all. The model carries no review-shaped field: ratings,
 * counts and summaries come only from the archive.
 */
class GooglePoiModelsSlice1Test {

    @Test
    fun `a blank info is empty and any place fact clears it`() {
        assertTrue(GooglePoiInfo().isEmpty)
        assertFalse(GooglePoiInfo(priceText = "$$").isEmpty)
        assertFalse(GooglePoiInfo(website = "https://x.example").isEmpty)
        assertFalse(GooglePoiInfo(phone = "1").isEmpty)
        assertFalse(GooglePoiInfo(statusText = "Open").isEmpty)
        assertFalse(GooglePoiInfo(fuelPrice = FuelPrice("$6.20", "Regular")).isEmpty)
        assertFalse(GooglePoiInfo(hours = listOf("Tue: 9-5")).isEmpty)
        assertFalse(GooglePoiInfo(photoUrls = listOf("https://x")).isEmpty)
        assertFalse(
            GooglePoiInfo(popularTimes = PoiPopularTimes(listOf(PoiDayBusyness(1, emptyList())))).isEmpty,
        )
    }

    @Test
    fun `fields that never render alone do not clear emptiness`() {
        // openNow, category and priceLevel carry no section of their own, so an info
        // with only those is still nothing worth showing.
        assertTrue(GooglePoiInfo(openNow = true).isEmpty)
        assertTrue(GooglePoiInfo(category = "Cafe").isEmpty)
        assertTrue(GooglePoiInfo(priceLevel = 2).isEmpty)
    }

    @Test
    fun `busyness models hold their documented ranges`() {
        val hour = PoiHourBusyness(hour = 9, occupancy = 80)
        assertEquals(9, hour.hour)
        assertEquals(PoiDayBusyness(dayOfWeek = 2, hours = listOf(hour)), PoiDayBusyness(2, listOf(hour)))
    }

    @Test
    fun `poi sections are details and photos only`() {
        assertEquals(listOf(PoiSection.DETAILS, PoiSection.PHOTOS), PoiSection.entries.toList())
    }

    @Test
    fun `a pin carries the minimum a map marker needs`() {
        val pin = PoiPin(id = "p1", name = "Cafe", lat = 37.0, lng = -122.0)
        assertEquals("p1", pin.id)
        assertEquals("Cafe", pin.name)
        assertNull(pin.category)
        // Data-class copy keeps identity while restyling.
        assertEquals("Diner", pin.copy(name = "Diner").name)
        assertEquals(pin.id, pin.copy(name = "Diner").id)
    }

    @Test
    fun `fuel price keeps google formatted text verbatim`() {
        assertEquals(FuelPrice("$6.20", "Regular"), FuelPrice(price = "$6.20", grade = "Regular"))
    }
}
