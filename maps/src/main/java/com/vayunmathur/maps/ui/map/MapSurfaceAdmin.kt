package com.vayunmathur.maps.ui.map

import com.vayunmathur.maps.data.SpecificFeature
import com.vayunmathur.maps.data.PoiSearch

internal fun adminUpgradeFor(
    place: com.vayunmathur.maps.data.SpecificFeature.GenericPlace,
    candidates: List<com.vayunmathur.maps.data.SpecificFeature>,
): com.vayunmathur.maps.data.SpecificFeature? {
    val wanted = com.vayunmathur.maps.data.PoiSearch.normalize(place.name)
    if (wanted.isEmpty()) return null
    return candidates.firstOrNull { candidate ->
        val name = when (candidate) {
            is com.vayunmathur.maps.data.SpecificFeature.Admin0Label -> candidate.name
            is com.vayunmathur.maps.data.SpecificFeature.Admin1Label -> candidate.name
            is com.vayunmathur.maps.data.SpecificFeature.Admin2Label -> candidate.name
            else -> return@firstOrNull false
        }
        com.vayunmathur.maps.data.PoiSearch.normalize(name) == wanted &&
            regionMaskFor(candidate) != null
    }
}
