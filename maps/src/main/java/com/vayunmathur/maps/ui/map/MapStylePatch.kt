package com.vayunmathur.maps.ui.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

// Native basemap layers suppressed at runtime — amenities come from the archive's
// offline POI index (custom overlay layer). Keeping this in code (vs editing style.json) makes it
// OTA-swappable per Decision D1.
private val SUPPRESSED_LAYERS = setOf("pois")

fun patchStyleForHybrid(
    jsonString: String,
    baseLocalUrl: String,
    hybridUrl: String,
    dark: Boolean = false,
): String {
    val json = Json { ignoreUnknownKeys = true }
    val root = json.parseToJsonElement(jsonString).jsonObject

    val newSources = buildJsonObject {
        putJsonObject("protomaps_base") {
            put("type", "vector")
            put("url", baseLocalUrl)
        }
        putJsonObject("protomaps_hybrid") {
            put("type", "vector")
            put("url", hybridUrl)
        }
    }

    val oldLayers = root["layers"]?.jsonArray ?: buildJsonArray {}
    val newLayers = buildJsonArray {
        oldLayers.forEach { layerElement ->
            val layer = layerElement.jsonObject
            val id = layer["id"]?.jsonPrimitive?.content ?: ""
            val type = layer["type"]?.jsonPrimitive?.content ?: ""

            // Suppress native basemap POIs at runtime (Decision D1) — amenities
            // come from the offline POI index, rendered on the custom overlay layer. Dropping
            // the source layer here (rather than editing style.json) keeps it
            // OTA-swappable. Also drops the would-be _base/_hybrid variants.
            if (id in SUPPRESSED_LAYERS) return@forEach

            // Dark palette (P14): recolor the base Protomaps paint at runtime so
            // we don't duplicate the 3544-line style.json. Only colour keys are
            // swapped; width/opacity/dasharray expressions are preserved.
            val darkPaint = if (dark) darkenPaint(id, layer["paint"] as? JsonObject) else null

            if (type == "background") {
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                })
            } else {
                // Zoom 0-7: Base Local
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_base")
                    put("source", "protomaps_base")
                    put("maxzoom", 7)
                })
                // Zoom 7+: Hybrid (Local Only)
                add(buildJsonObject {
                    layer.forEach { (k, v) -> if (!(dark && k == "paint")) put(k, v) }
                    if (darkPaint != null) put("paint", darkPaint)
                    put("id", "${id}_hybrid")
                    put("source", "protomaps_hybrid")
                    put("minzoom", 7)
                })
            }
        }
    }

    return buildJsonObject {
        root.forEach { (k, v) -> if (k != "sources" && k != "layers") put(k, v) }
        put("sources", newSources)
        put("layers", newLayers)
    }.toString()
}

/**
 * Rebuild a layer's `paint` for the dark palette (P14): copy every property
 * verbatim and only swap the colour keys, so zoom-driven width/opacity/dasharray
 * expressions keep working. `text-halo-width` etc. are left untouched. Layers
 * without a colour key (e.g. the icon-only `roads_oneway`) come back unchanged.
 */
private fun darkenPaint(id: String, paint: JsonObject?): JsonObject? {
    if (paint == null) return null
    val base = darkBaseColor(id)
    val (text, halo) = darkTextColors(id)
    return buildJsonObject {
        paint.forEach { (k, v) ->
            when (k) {
                "background-color", "fill-color", "line-color" -> put(k, base)
                "text-color" -> put(k, text)
                "text-halo-color" -> put(k, halo)
                else -> put(k, v)
            }
        }
    }
}

/**
 * Dark fill/line/background colour for a base Protomaps layer. Keyed by layer id
 * (the style's ids are stable), falling through prefix/substring rules for the
 * many road variants. Casing colours must be matched before the highway/major
 * rules because e.g. `roads_highway_casing_early` contains both tokens.
 */
private fun darkBaseColor(id: String): String = when {
    id == "background" || id == "earth" -> "#1b1d22"
    id == "water" -> "#0d1b2a"
    id == "water_stream" || id == "water_river" -> "#24455f"
    id == "landcover" -> "#1f2a22"
    id == "landuse_park" -> "#1e2b20"
    id == "landuse_urban_green" -> "#23362a"
    id == "landuse_hospital" -> "#2b2528"
    id == "landuse_industrial" -> "#20262b"
    id == "landuse_school" -> "#282520"
    id == "landuse_beach" -> "#2c2a22"
    id == "landuse_zoo" -> "#213030"
    id == "landuse_aerodrome" -> "#212228"
    id == "landuse_runway" -> "#2b2d33"
    id == "landuse_pedestrian" -> "#242229"
    id == "landuse_pier" -> "#202225"
    id.startsWith("landuse") -> "#1f2126"
    id == "buildings" -> "#22262c"
    id.startsWith("boundaries") -> "#4a4f57"
    id == "roads_rail" -> "#3a3e45"
    id.startsWith("roads_runway") || id.startsWith("roads_taxiway") -> "#2b2d33"
    id.contains("casing") -> "#111318"
    id.startsWith("roads_tunnels") -> "#2b2e35"
    id.contains("highway") || id.contains("major") || id.contains("link") -> "#464b54"
    id.startsWith("roads") -> "#34383f"
    else -> "#26282e"
}

/**
 * Dark (text-color, text-halo-color) for a label/POI symbol layer: light text on
 * a near-black halo so labels stay legible over the dark basemap. Water labels
 * keep a blue tint over the dark-navy water.
 */
private fun darkTextColors(id: String): Pair<String, String> = when (id) {
    "places_locality" -> "#e4e8ee" to "#101216"
    "places_country" -> "#9aa0aa" to "#101216"
    "places_region" -> "#80868f" to "#101216"
    "places_subplace" -> "#b0b6c0" to "#101216"
    "earth_label_islands" -> "#9aa0aa" to "#101216"
    "water_waterway_label", "water_label_ocean", "water_label_lakes" -> "#6f8fce" to "#0d1b2a"
    "roads_shields" -> "#c8ccd4" to "#101216"
    "roads_labels_major", "roads_labels_minor", "address_label" -> "#b8bdc6" to "#101216"
    else -> "#c9ced6" to "#101216"
}
