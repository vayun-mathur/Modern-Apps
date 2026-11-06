package com.vayunmathur.maps.data.google

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/3 (maps): the [GooglePoiInfo]/[GooglePoiPin] model layer —
 * in particular [GooglePoiInfo.isEmpty], the gate that decides whether the
 * place sheet shows an enrichment section at all.
 */
class GooglePoiModelsSlice1Test {

    @Test
    fun `a blank info is empty and any signal clears it`() {
        assertTrue(GooglePoiInfo().isEmpty)
        assertFalse(GooglePoiInfo(rating = 4.5).isEmpty)
        assertFalse(GooglePoiInfo(reviewCount = 10).isEmpty)
        assertFalse(GooglePoiInfo(priceText = "$$").isEmpty)
        assertFalse(GooglePoiInfo(website = "https://x.example").isEmpty)
        assertFalse(GooglePoiInfo(phone = "1").isEmpty)
        assertFalse(GooglePoiInfo(statusText = "Open").isEmpty)
        assertFalse(GooglePoiInfo(editorialSummary = "Nice").isEmpty)
        assertFalse(GooglePoiInfo(featuredReview = "Great").isEmpty)
        assertFalse(GooglePoiInfo(fuelPrice = FuelPrice("$6.20", "Regular")).isEmpty)
        assertFalse(GooglePoiInfo(hours = listOf("Tue: 9-5")).isEmpty)
        assertFalse(GooglePoiInfo(photoUrls = listOf("https://x")).isEmpty)
        assertFalse(
            GooglePoiInfo(reviews = listOf(GoogleReview("A", null, 5, null, null))).isEmpty,
        )
        assertFalse(
            GooglePoiInfo(popularTimes = PoiPopularTimes(listOf(PoiDayBusyness(1, emptyList())))).isEmpty,
        )
    }

    @Test
    fun `fields that never render alone do not clear emptiness`() {
        // openNow, category, priceLevel and featureId carry no section of their
        // own, so an info with only those is still nothing worth showing.
        assertTrue(GooglePoiInfo(openNow = true).isEmpty)
        assertTrue(GooglePoiInfo(category = "Cafe").isEmpty)
        assertTrue(GooglePoiInfo(priceLevel = 2).isEmpty)
        assertTrue(GooglePoiInfo(featureId = "0x1:0x2").isEmpty)
    }

    @Test
    fun `review and busyness models hold their documented ranges`() {
        val review = GoogleReview(
            author = "A",
            authorPhoto = null,
            rating = 5,
            relativeTime = "yesterday",
            text = "Good",
            photos = listOf("https://x"),
        )
        assertEquals("A", review.author)
        assertEquals(emptyList(), GoogleReview("B", null, 1, null, null).photos)

        val hour = PoiHourBusyness(hour = 9, occupancy = 80)
        assertEquals(9, hour.hour)
        assertEquals(PoiDayBusyness(dayOfWeek = 2, hours = listOf(hour)), PoiDayBusyness(2, listOf(hour)))
    }

    @Test
    fun `poi sections cover details photos and reviews`() {
        assertEquals(3, PoiSection.entries.size)
        assertTrue(PoiSection.entries.containsAll(listOf(PoiSection.DETAILS, PoiSection.PHOTOS, PoiSection.REVIEWS)))
    }

    @Test
    fun `a pin carries the minimum a map marker needs`() {
        val pin = GooglePoiPin(id = "p1", name = "Cafe", lat = 37.0, lng = -122.0)
        assertEquals("p1", pin.id)
        assertEquals("Cafe", pin.name)
        assertNull(pin.category)
        assertNull(pin.rating)
        assertEquals(0.0, pin.prominence)
        // Data-class copy keeps identity while restyling.
        assertEquals("Diner", pin.copy(name = "Diner").name)
        assertEquals(pin.id, pin.copy(name = "Diner").id)
    }

    @Test
    fun `fuel price keeps google formatted text verbatim`() {
        assertEquals(FuelPrice("$6.20", "Regular"), FuelPrice(price = "$6.20", grade = "Regular"))
    }
}
