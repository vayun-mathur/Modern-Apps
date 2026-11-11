package com.vayunmathur.maps.data

/**
 * One ambient POI pin on the map, from the archive's offline POI index (`PoiIndex`):
 * the minimum a pin needs — a stable id, a name, a position and the category label that
 * drives its colour. Tapping one re-selects the place, whose sheet then reads the
 * archive's attributes (hours, phone, rating, review summary) the same way search does.
 *
 * Formerly `GooglePoiPin`, from when pins were discovered by a Google viewport scrape;
 * nothing about a pin comes from Google any more.
 */
data class PoiPin(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: String? = null,
)
