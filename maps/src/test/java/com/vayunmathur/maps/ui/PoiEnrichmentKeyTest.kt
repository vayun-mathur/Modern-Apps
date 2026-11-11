package com.vayunmathur.maps.ui

import com.vayunmathur.maps.data.google.GooglePoiInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The coarse place-details identity behind the inner place sheet's contentKey.
 *
 * Late-arriving details grow the sheet content after measurement, which the scaffold's
 * learned ceiling would otherwise never re-learn — so the key must move when the details
 * arrive. But it must stay coarse (counts/presence, not the lists).
 */
class PoiEnrichmentKeyTest {

    @Test
    fun `null enrichment keys to zero`() {
        assertEquals(PoiEnrichmentKey(0, hasHours = false, hasPopularTimes = false), PoiEnrichmentKey.of(null))
    }

    @Test
    fun `arriving photos move the key`() {
        val before = PoiEnrichmentKey.of(GooglePoiInfo())
        val after = PoiEnrichmentKey.of(GooglePoiInfo(photoUrls = listOf("u1", "u2")))
        assertNotEquals(before, after)
    }

    @Test
    fun `arriving hours move the key`() {
        val before = PoiEnrichmentKey.of(GooglePoiInfo())
        val after = PoiEnrichmentKey.of(GooglePoiInfo(hours = listOf("Tuesday: 9 AM–5 PM")))
        assertNotEquals(before, after)
    }

    @Test
    fun `photo url churn without growth keeps the key`() {
        val a = PoiEnrichmentKey.of(GooglePoiInfo(photoUrls = listOf("u1", "u2")))
        val b = PoiEnrichmentKey.of(GooglePoiInfo(photoUrls = listOf("v1", "v2")))
        assertEquals(a, b)
    }
}
