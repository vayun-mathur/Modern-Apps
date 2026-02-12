package com.vayunmathur.maps.data

import com.vayunmathur.maps.Wikidata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@Serializable
sealed interface SpecificFeature {
    interface RoutableFeature : SpecificFeature {
        val position: Position
        val name: String
    }

    @Serializable
    data class Admin0Label(val iso3166_1: String, val wikipedia: String, val name: String) : SpecificFeature
    @Serializable
    data class Admin1Label(val iso3166_2: String, val wikipedia: String, val name: String) : SpecificFeature
    @Serializable
    data class Restaurant(override val name: String, val phone: String?, val website: String?, val menu: String?, val openingHours: OpeningHours?,
                          override val position: Position): RoutableFeature
    @Serializable
    data class Route(val waypoints: List<RoutableFeature?>) : SpecificFeature
}

typealias Feature1 = Feature<Geometry, JsonObject?>

fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

suspend fun parse(feature: Feature1, db: AmenityDatabase): SpecificFeature? {
    val id = feature.id?.jsonPrimitive?.content?.toULong() ?: 0uL
    val geometry = feature.geometry
    val properties = feature.properties ?: return null
    return when(properties.string("kind")) {
        "country" -> {
            val wiki = Wikidata.get(properties.string("wikidata")!!)
            SpecificFeature.Admin0Label(wiki.getProperty("P297")!!, wiki.getWikipedia()!!, properties.string("name:en")!!)
        }
        "region" -> {
            val wiki = Wikidata.get(properties.string("wikidata")!!)
            SpecificFeature.Admin1Label(wiki.getProperty("P300")!!, wiki.getWikipedia()!!, properties.string("name:en")!!)
        }
        "restaurant", "fast_food", "cafe", "bar" -> {
            val tags = db.tagDao().getTags(id.toLong()).associate { it.key to it.value }
            SpecificFeature.Restaurant(tags["name"] ?: "", tags["phone"], tags["website"], tags["website:menu"], tags["opening_hours"]?.let { OpeningHours.from(it) }, (geometry as Point).coordinates)
        }
        else -> null
    }
}