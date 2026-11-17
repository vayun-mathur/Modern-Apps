package com.vayunmathur.maps.data

import com.vayunmathur.maps.domain.RouteService
import java.net.URLDecoder

/**
 * A target extracted from an external `geo:` / `google.navigation:` URI or a
 * Google-Maps web link, so this app can be the system maps handler on a
 * de-Googled phone (extended for turn-by-turn).
 *
 * [zoom] is the caller's requested camera zoom (`geo:...?z=17`, `/@lat,lng,15z`)
 * when it carried one. [navigate] is true for `google.navigation:` and Google
 * `/maps/dir/` links, which should START guidance rather than just open a place;
 * [mode] is the requested travel mode when the link specified one.
 */
data class MapLink(
    val query: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val zoom: Double? = null,
    val navigate: Boolean = false,
    val mode: RouteService.TravelMode? = null,
) {
    val hasTarget: Boolean get() = !query.isNullOrBlank() || (lat != null && lng != null)
}

/**
 * Parses the links other apps hand to a maps app:
 *  - `geo:38.5,-121.7`                → a point
 *  - `geo:0,0?q=Coffee`               → a search
 *  - `geo:38.5,-121.7?q=Pier 39`      → a named place near a point
 *  - `geo:0,0?q=38.5,-121.7(Label)`   → a labelled point
 *  - `google.navigation:q=38.5,-121.7&mode=d`  → navigate (drive) to a point
 *  - `google.navigation:q=Pier 39&mode=w`      → navigate (walk) to a search
 *  - `https://www.google.com/maps/place/Foo/@38.5,-121.7,15z` → a place / point
 *  - `https://www.google.com/maps/search/coffee` / `?q=...`    → a search
 *  - `https://www.google.com/maps/dir/?...&destination=...`    → navigate
 *
 * Pure Kotlin (no `android.net.Uri`) so it's unit-testable and never throws:
 * a malformed/unknown link returns null and the caller does nothing.
 */
object MapLinkParser {
    private val COORD = Regex("""(-?\d{1,3}\.\d+),\s*(-?\d{1,3}\.\d+)""")
    private val AT = Regex("""@(-?\d{1,3}\.\d+),(-?\d{1,3}\.\d+)""")
    /** Zoom suffix on a Google web link: `/@lat,lng,15z`. */
    private val AT_ZOOM = Regex("""@-?\d{1,3}\.\d+,-?\d{1,3}\.\d+,(\d+(?:\.\d+)?)z""")
    /** Parenthesised label in `geo:0,0?q=lat,lng(Label)`. */
    private val LABEL = Regex("""\(([^)]+)\)""")
    /** Camera zoom range honoured from links; junk ignored. */
    private const val MIN_ZOOM = 1.0
    private const val MAX_ZOOM = 21.0
    private val PLACE_PATH = Regex("""/place/([^/@?]+)""")
    private val SEARCH_PATH = Regex("""/search/([^/@?]+)""")

    fun parse(raw: String): MapLink? {
        val link = runCatching {
            when {
                raw.startsWith("geo:", ignoreCase = true) -> parseGeo(raw)
                raw.startsWith("google.navigation:", ignoreCase = true) -> parseNavigation(raw)
                "/maps" in raw || "maps.google" in raw ||
                    "maps.app.goo.gl" in raw || "goo.gl/maps" in raw -> parseMaps(raw)
                else -> null
            }
        }.getOrNull()
        return link?.takeIf { it.hasTarget }
    }

    private fun parseGeo(raw: String): MapLink {
        val body = raw.substring(4) // after "geo:"
        val coordPart = body.substringBefore("?")
        var lat = COORD.find(coordPart)?.groupValues?.get(1)?.toDoubleOrNull()
        var lng = COORD.find(coordPart)?.groupValues?.get(2)?.toDoubleOrNull()
        if (lat == 0.0 && lng == 0.0) { lat = null; lng = null } // 0,0 = "no point, see ?q"
        // RFC-style zoom: geo:lat,lng?z=17 (1..21). Honoured for the camera; junk ignored.
        val zoom = queryParam(raw, "z")?.toDoubleOrNull()?.takeIf { it in MIN_ZOOM..MAX_ZOOM }

        val q = queryParam(raw, "q")?.let { decode(it) }
        if (!q.isNullOrBlank()) {
            // ?q can be "lat,lng(Label)", a bare "lat,lng", or an address/name.
            val label = LABEL.find(q)?.groupValues?.get(1)
            val qc = COORD.find(q)
            if (qc != null && lat == null) {
                return MapLink(
                    query = label,
                    lat = qc.groupValues[1].toDoubleOrNull(),
                    lng = qc.groupValues[2].toDoubleOrNull(),
                    zoom = zoom,
                )
            }
            val text = label ?: q.takeUnless { COORD.matches(it.trim()) }
            return MapLink(query = text, lat = lat, lng = lng, zoom = zoom)
        }
        return MapLink(lat = lat, lng = lng, zoom = zoom)
    }

    /** `google.navigation:q=<lat,lng | address>[&mode=d/w/b/l]` → start guidance.
     *  NB the params follow the scheme colon directly (no `?`), unlike geo:. */
    private fun parseNavigation(raw: String): MapLink {
        val body = raw.substringAfter("google.navigation:", "")
        val mode = travelModeFromLetter(paramIn(body, "mode"))
        val q = (paramIn(body, "q") ?: paramIn(body, "ll"))?.let { decode(it) }
            ?: return MapLink(navigate = true)
        COORD.matchEntire(q.trim())?.let {
            return MapLink(
                lat = it.groupValues[1].toDoubleOrNull(),
                lng = it.groupValues[2].toDoubleOrNull(),
                navigate = true,
                mode = mode,
            )
        }
        return MapLink(query = q, navigate = true, mode = mode)
    }

    private fun parseMaps(raw: String): MapLink {
        val lat = atCoord(raw, 1)
        val lng = atCoord(raw, 2)
        // Google web links carry zoom after the @coords: /@38.5,-121.7,15z.
        val zoom = AT_ZOOM.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { it in MIN_ZOOM..MAX_ZOOM }

        // Directions link → navigate. Destination comes from the api=1 `destination=`
        // / classic `daddr=` param, else the last path segment after `/dir/`.
        if (isDirLink(raw)) return parseDirLink(raw, lat, lng)
        return parsePlaceLink(raw, lat, lng, zoom)
    }

    /** Whether [raw] is a directions link (path, query, or bare `/dir`). */
    private fun isDirLink(raw: String): Boolean =
        "/dir/" in raw || "/dir?" in raw || raw.endsWith("/dir")

    private fun parseDirLink(raw: String, lat: Double?, lng: Double?): MapLink {
        val mode = travelModeFromWord(
            queryParam(raw, "travelmode") ?: queryParam(raw, "dirflg")
        )
        val destRaw = queryParam(raw, "destination") ?: queryParam(raw, "daddr")
            ?: dirDestinationSegment(raw)
        val dest = destRaw?.let { decode(it.replace('+', ' ')) }?.takeIf { it.isNotBlank() }
        if (dest != null) return destLink(dest, mode)
        // No parseable destination but coords in the URL: navigate to those.
        if (lat != null && lng != null) return MapLink(lat = lat, lng = lng, navigate = true, mode = mode)
        return MapLink(navigate = true, mode = mode)
    }

    private fun destLink(dest: String, mode: RouteService.TravelMode?): MapLink {
        COORD.matchEntire(dest.trim())?.let {
            return MapLink(
                lat = it.groupValues[1].toDoubleOrNull(),
                lng = it.groupValues[2].toDoubleOrNull(),
                navigate = true,
                mode = mode,
            )
        }
        return MapLink(query = dest, navigate = true, mode = mode)
    }

    private fun parsePlaceLink(raw: String, lat: Double?, lng: Double?, zoom: Double?): MapLink {
        val place = PLACE_PATH.find(raw)?.groupValues?.get(1)
        val search = SEARCH_PATH.find(raw)?.groupValues?.get(1)
        val q = queryParam(raw, "q") ?: queryParam(raw, "query")
        val query = (place ?: search ?: q)?.let { decode(it.replace('+', ' ')) }?.takeIf { it.isNotBlank() }
        // A query that's really coordinates → treat as a point.
        query?.trim()?.let { COORD.matchEntire(it) }?.let {
            return MapLink(
                lat = it.groupValues[1].toDoubleOrNull(),
                lng = it.groupValues[2].toDoubleOrNull(),
                zoom = zoom,
            )
        }
        return MapLink(query = query, lat = lat, lng = lng, zoom = zoom)
    }

    /** One @-coordinate group from the URL, or null. */
    private fun atCoord(raw: String, group: Int): Double? =
        AT.find(raw)?.groupValues?.get(group)?.toDoubleOrNull()

    /** Last meaningful segment after `/dir/` (skipping empties, `@camera` and `data=`). */
    private fun dirDestinationSegment(raw: String): String? {
        val after = raw.substringAfter("/dir/", "").substringBefore('?')
        if (after.isBlank()) return null
        return after.split('/')
            .filter { it.isNotBlank() && !it.startsWith("@") && !it.startsWith("data=") }
            .lastOrNull()
    }

    private fun travelModeFromLetter(letter: String?): RouteService.TravelMode? = when (letter?.lowercase()) {
        "d", "l" -> RouteService.TravelMode.DRIVE // l = two-wheeler; closest is drive
        "w" -> RouteService.TravelMode.WALK
        "b" -> RouteService.TravelMode.BICYCLE
        else -> null
    }

    private fun travelModeFromWord(word: String?): RouteService.TravelMode? = when (word?.lowercase()) {
        "driving", "d" -> RouteService.TravelMode.DRIVE
        "walking", "w" -> RouteService.TravelMode.WALK
        "bicycling", "b" -> RouteService.TravelMode.BICYCLE
        "transit", "r" -> RouteService.TravelMode.TRANSIT
        else -> null
    }

    private fun queryParam(raw: String, key: String): String? =
        paramIn(raw.substringAfter('?', ""), key)

    /** Find [key] in a raw `k=v&k=v` param string (no leading `?`). */
    private fun paramIn(query: String, key: String): String? {
        if (query.isEmpty()) return null
        return query.split('&').firstNotNullOfOrNull { p ->
            val (k, v) = p.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k.equals(key, ignoreCase = true) && v.isNotEmpty()) v else null
        }
    }

    private fun decode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
