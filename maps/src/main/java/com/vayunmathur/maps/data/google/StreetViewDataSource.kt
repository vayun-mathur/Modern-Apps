package com.vayunmathur.maps.data.google

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import com.vayunmathur.library.network.NetworkClient
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Keyless Google Street View.
 *
 * Keyless data layer: the device calls the same undocumented
 * endpoints a logged-out browser / the JS Maps API does — no API key, authorised
 * by a browser-like `User-Agent` + `Referer`. Two metadata endpoints feed one
 * parser (both return a deeply nested *positional* JSON array, no field names):
 *  - `GeoPhotoService.SingleImageSearch` — nearest pano to a lat/lng (a `cb(…)`
 *    callback wrapper);
 *  - `photometa/v1` — a pano by id, used to walk to a neighbour (a `)]}'` guard).
 * Tiles come from `streetviewpixels-pa.googleapis.com/v1/tile` (keyless JPEG).
 *
 * RENDERER NOTE: this returns a single stitched *equirectangular* [Bitmap]; the
 * viewer ([ui.streetview.StreetViewScreen]) projects it onto the inside of a GLES
 * sphere via the shared `PanoramaSphere` renderer.
 *
 * All network runs on [Dispatchers.IO]; every accessor is null-safe so a Google
 * reshape degrades to "no imagery" instead of throwing. Pano metadata is cached in
 * a small bounded LRU. Bitmaps are NOT cached here (they're large and owned by the
 * viewer for its current pano).
 *
 * NOTE (on-device): the live fetch + tile stitch needs a device with network — it
 * can't be exercised at compile time.
 */
object StreetViewDataSource {

    // Nearest-pano lookup (SingleImageSearch). {LAT}/{LNG} = the query point;
    // `!2d50` is the search radius in metres. `&callback=cb` → a JS callback body.
    private const val META_URL =
        "https://maps.googleapis.com/maps/api/js/GeoPhotoService.SingleImageSearch?pb=" +
            "!1m5!1sapiv3!5sUS!11m2!1m1!1b0!2m4!1m2!3d{LAT}!4d{LNG}!2d50!3m10!2m2!1sen!2sUS" +
            "!9m1!1e2!11m4!1m3!1e2!2b1!3e2!4m10!1e1!1e2!1e3!1e4!1e8!1e6!5m1!1e2!6m1!1e2&callback=cb"

    // By-pano-id metadata (photometa/v1). `!3m3!1m2!1e2!2s{PANOID}` selects the pano.
    private const val PANO_URL =
        "https://www.google.com/maps/photometa/v1?authuser=0&hl=en&gl=us&pb=" +
            "!1m4!1smaps_sv.tactile!11m2!2m1!1b1!2m2!1sen!2sus!3m3!1m2!1e2!2s{PANOID}" +
            "!4m57!1e1!1e2!1e3!1e4!1e5!1e6!1e8!1e12!2m1!1e1!4m1!1i48!5m1!1e1!5m1!1e2!6m1!1e1!6m1!1e2" +
            "!9m36!1m3!1e2!2b1!3e2!1m3!1e2!2b0!3e3!1m3!1e3!2b1!3e2!1m3!1e3!2b0!3e3!1m3!1e8!2b0!3e3" +
            "!1m3!1e1!2b0!3e3!1m3!1e4!2b0!3e3!1m3!1e10!2b1!3e2!1m3!1e10!2b0!3e3"

    // The consumer equirect tile endpoint maps.google.com renders — keyless JPEG.
    private const val TILE_URL =
        "https://streetviewpixels-pa.googleapis.com/v1/tile" +
            "?cb_client=maps_sv.tactile&panoid={PANOID}&x={X}&y={Y}&zoom={Z}&nbt=1&fover=2"

    // Browser-like identity — these headers ARE the credential (no key). The tile
    // host also requires the Google referer.
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val REQUEST_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://www.google.com/maps/",
    )

    // Cap the stitched equirect width so a full-res pyramid (16384×8192 ≈ 400 MB
    // decoded) can't blow up memory. Also keeps the bitmap inside GL_MAX_TEXTURE_SIZE,
    // which is at least 4096 on every device this app runs on.
    private const val MAX_TILE_WIDTH = 4096

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PANO_ID = Regex("^[A-Za-z0-9_-]{20,25}$")

    // Neighbour de-clutter: drop same-spot historical panos, cap walk
    // reach, keep only the nearest pano per direction bucket.
    private const val SAME_SPOT_M = 4.0
    private const val MAX_WALK_M = 45.0
    private const val BUCKET_DEG = 30.0
    /** Fallback tile grid when the pano carries no level dims (modern 512·2^z shape). */
    private const val DEFAULT_TILE_W = 512
    private const val DEFAULT_TILE_H = 256
    private const val DEFAULT_TILE_SIZE = 512
    private const val DEFAULT_LEVEL_COUNT = 6
    /** XSSI guard prefixing photometa/v1 responses. */
    private const val XSSI_GUARD = ")]}'"
    private const val XSSI_GUARD_LEN = 4
    /** Success range for the tile fetch. */
    private const val HTTP_OK_MIN = 200
    private const val HTTP_OK_MAX = 299
    /** Mean Earth radius (m) for the neighbour-distance haversine. */
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val FULL_CIRCLE_DEG = 360.0
    private const val HALF_CIRCLE_DEG = 180.0

    /**
     * Test seams: metadata GET (body text or null) and tile GET (bytes or null).
     * Defaults hit the live network; tests replace them with canned payloads so
     * the parse/selection logic is verifiable without network.
     */
    internal var httpGet: (suspend (String) -> String?) = { getText(it) }
    internal var httpGetBytes: (suspend (String) -> ByteArray?) = { tileBytes(it) }

    // Bounded LRU (access-ordered) of resolved metadata, keyed by pano id and by a
    // rounded lat/lng probe. Stores null too (negative cache) so a spot with no
    // imagery isn't refetched on every reselect. Guarded by `synchronized`.
    private val cache = object : LinkedHashMap<String, StreetViewPano?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StreetViewPano?>) = size > 48
    }

    /**
     * Nearest pano to [lat],[lng], or null when there's no imagery nearby or the
     * scrape fails. Never throws.
     */
    suspend fun nearest(lat: Double, lng: Double): StreetViewPano? {
        // ~11 m grid: enough to dedupe repeated selects of the same place without
        // snapping distinct nearby places together.
        val key = "ll:${"%.4f".format(lat)},${"%.4f".format(lng)}"
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val pano = runCatching { fetchNearest(lat, lng) }.getOrNull()
        synchronized(cache) {
            cache[key] = pano
            if (pano != null) cache[pano.panoId] = pano
        }
        return pano
    }

    /**
     * Metadata for a specific [panoId] — used to walk to a neighbour. Null on
     * failure. Never throws.
     */
    suspend fun byPano(panoId: String): StreetViewPano? {
        synchronized(cache) { if (cache.containsKey(panoId)) return cache[panoId] }
        val pano = runCatching { fetchPano(panoId) }.getOrNull()
        synchronized(cache) { cache[panoId] = pano }
        return pano
    }

    private suspend fun fetchNearest(lat: Double, lng: Double): StreetViewPano? =
        withContext(Dispatchers.IO) {
            val url = META_URL.replace("{LAT}", lat.toString()).replace("{LNG}", lng.toString())
            val body = httpGet(url) ?: return@withContext null
            parsePano(body, lat, lng)
        }

    private suspend fun fetchPano(panoId: String): StreetViewPano? =
        withContext(Dispatchers.IO) {
            val body = httpGet(PANO_URL.replace("{PANOID}", panoId)) ?: return@withContext null
            // A by-id fetch has no query point; the parser reads the real position
            // from the response and only uses the fallback when it's missing.
            parsePano(body, 0.0, 0.0).takeIf { it != null && (it.lat != 0.0 || it.lng != 0.0) }
        }

    // --- panorama tiles → one equirect bitmap --------------------------------

    /**
     * Fetch [pano]'s equirectangular tiles at a bounded zoom level and stitch them
     * into one bitmap for the sphere viewer. Null when no tile could be fetched.
     *
     * The pyramid shape is taken from the pano's own [StreetViewPano.levelDims]
     * (modern captures are 512·2^z, pre-2016 are 416·2^z) so an old capture's grid
     * isn't over-requested into black bands.
     */
    suspend fun loadPanorama(pano: StreetViewPano): Bitmap? = withContext(Dispatchers.IO) {
        val dims = pano.levelDims.ifEmpty {
            List(DEFAULT_LEVEL_COUNT) { DEFAULT_TILE_W * (1 shl it) to DEFAULT_TILE_H * (1 shl it) }
        }
        val z = dims.indexOfLast { it.first in 1..MAX_TILE_WIDTH }.coerceAtLeast(0)
        val (w, h) = dims[z]
        val ts = pano.tileSize.coerceAtLeast(1)
        val cols = (w + ts - 1) / ts
        val rows = (h + ts - 1) / ts
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val placed = coroutineScope {
            val jobs = ArrayList<Deferred<Triple<Int, Int, Bitmap>?>>(cols * rows)
            for (y in 0 until rows) for (x in 0 until cols) {
                jobs += async(Dispatchers.IO) {
                    val bytes = tile(pano.panoId, x, y, z) ?: return@async null
                    val t = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@async null
                    Triple(x, y, t)
                }
            }
            var n = 0
            for (job in jobs) {
                val t = job.await() ?: continue
                val px = t.first * ts
                val py = t.second * ts
                // Crop edge tiles to the image bounds — past them is padding.
                val srcW = minOf(ts, w - px).coerceAtMost(t.third.width)
                val srcH = minOf(ts, h - py).coerceAtMost(t.third.height)
                if (srcW > 0 && srcH > 0) {
                    canvas.drawBitmap(t.third, Rect(0, 0, srcW, srcH), Rect(px, py, px + srcW, py + srcH), null)
                    n++
                }
                t.third.recycle()
            }
            n
        }
        if (placed == 0) {
            bmp.recycle()
            null
        } else {
            bmp
        }
    }

    private suspend fun tile(panoId: String, x: Int, y: Int, zoom: Int): ByteArray? {
        val url = TILE_URL
            .replace("{PANOID}", panoId)
            .replace("{X}", x.toString())
            .replace("{Y}", y.toString())
            .replace("{Z}", zoom.toString())
        return httpGetBytes(url)
    }

    private suspend fun tileBytes(url: String): ByteArray? {
        val (status, bytes) = runCatching {
            NetworkClient.performRequestBytes(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return null
        return if (status in HTTP_OK_MIN..HTTP_OK_MAX && bytes.isNotEmpty()) bytes else null
    }

    // --- HTTP + parsing ------------------------------------------------------

    /** GET the body text, or null on any non-2xx / transport error. `useSystemTrust`
     *  because these are arbitrary external Google hosts, not pinned first-party. */
    private suspend fun getText(url: String): String? {
        val resp = runCatching {
            NetworkClient.performRequest(url = url, headers = REQUEST_HEADERS, useSystemTrust = true)
        }.getOrNull() ?: return null
        return if (resp.isSuccess) resp.body else null
    }

    /**
     * Parse a SingleImageSearch / photometa response into a [StreetViewPano].
     * [lat]/[lng] are the fallback position used only when the response omits one.
     * Only the pano id is required; everything else degrades to null.
     */
    private fun parsePano(raw: String, lat: Double, lng: Double): StreetViewPano? {
        val root = runCatching { json.parseToJsonElement(unwrap(raw)) }.getOrNull() ?: return null
        val panoNode = panoNode(root) ?: return null
        val panoId = panoId(panoNode) ?: return null

        val tileSize = panoNode.at(2, 3, 1, 0).int()
            ?: panoNode.at(2, 3, 1, 1).int() ?: DEFAULT_TILE_SIZE
        val levels = panoNode.at(2, 3, 0).arr()?.size ?: DEFAULT_LEVEL_COUNT
        // Per-level [h, w] (nested one deeper) → (w, h). Old captures are 416·2^z.
        val levelDims = panoNode.at(2, 3, 0).arr()?.mapNotNull { lvl ->
            levelDim(lvl)
        }.orEmpty()

        val posNode = panoNode.at(5, 0, 1)
        val pLat = posNode.at(0, 2).dbl() ?: lat
        val pLng = posNode.at(0, 3).dbl() ?: lng
        val heading = posNode.at(2, 0).dbl() ?: 0.0

        val address = panoNode.at(3, 2, 0, 0).str()
        val copyright = panoNode.at(4, 0, 0, 0, 0).str()
        val year = panoNode.at(6, 7, 0).int()
        val month = panoNode.at(6, 7, 1).int()
        val neighbors = panoLinks(panoNode, panoId, pLat, pLng)

        return StreetViewPano(
            panoId = panoId, lat = pLat, lng = pLng, headingDeg = heading,
            tileSize = tileSize, maxZoom = levels, levelDims = levelDims,
            addressLabel = address, copyright = copyright,
            captureYear = year, captureMonth = month, neighbors = neighbors,
        )
    }

    /** Pano node: root[1], or root[1][0] when photometa wraps one deeper. */
    private fun panoNode(root: JsonElement): JsonElement? {
        // Two nestings: SingleImageSearch puts the pano node at root[1]; photometa/v1
        // wraps it one deeper at root[1][0]. Pick whichever carries the [1][1] id.
        val direct = root.at(1)
        if (direct.at(1, 1).str()?.let(PANO_ID::matches) == true) return direct
        return root.at(1, 0)
    }

    private fun panoId(panoNode: JsonElement): String? =
        panoNode.at(1, 1).str()?.takeIf { PANO_ID.matches(it) }
            ?: firstMatchingString(panoNode) { PANO_ID.matches(it) }

    /** Per-level [h, w] (nested one deeper) → (w, h), or null when unusable. */
    private fun levelDim(lvl: JsonElement): Pair<Int, Int>? {
        val h = lvl.at(0, 0).int()
        val w = lvl.at(0, 1).int()
        if (hasSize(w, h)) return w!! to h!!
        return null
    }

    /** Whether [w]/[h] are both present and positive. */
    private fun hasSize(w: Int?, h: Int?): Boolean =
        w != null && h != null && w > 0 && h > 0

    /** Local-graph neighbours: nearest pano per direction bucket. */
    private fun panoLinks(
        panoNode: JsonElement,
        panoId: String,
        pLat: Double,
        pLng: Double,
    ): List<StreetViewLink> {
        // Local graph: [ [2,id], _, [ [_,_,lat,lng], … ] ]. Index 0 is this pano.
        val graph = panoNode.at(5, 0, 3, 0).arr().orEmpty()
        val raws = graph.mapNotNull { g ->
            val id = g.at(0, 1).str()?.takeIf { PANO_ID.matches(it) } ?: return@mapNotNull null
            val gLa = g.at(2, 0, 2).dbl() ?: return@mapNotNull null
            val gLn = g.at(2, 0, 3).dbl() ?: return@mapNotNull null
            Triple(id, gLa, gLn)
        }

        return raws.asSequence()
            .filter { it.first != panoId }
            .map { r ->
                val dm = haversine(pLat, pLng, r.second, r.third)
                val bd = bearing(pLat, pLng, r.second, r.third)
                StreetViewLink(r.first, r.second, r.third, bd, dm)
            }
            .filter { it.distanceM in SAME_SPOT_M..MAX_WALK_M }
            .sortedBy { it.distanceM }
            .fold(mutableListOf<StreetViewLink>()) { keep, link ->
                val crowded = keep.any { abs(angleDelta(it.bearingDeg, link.bearingDeg)) < BUCKET_DEG }
                if (!crowded) keep.add(link)
                keep
            }
            .sortedBy { it.bearingDeg }
    }

    /** Strip the response envelope: photometa/v1's `)]}'` XSSI guard, or
     *  SingleImageSearch's `/**/cb && cb( … )` callback wrapper. */
    private fun unwrap(raw: String): String {
        val t = raw.trim()
        if (t.startsWith(XSSI_GUARD)) return t.substring(XSSI_GUARD_LEN).trimStart('\n', '\r', ' ')
        val open = t.indexOf('(')
        val close = t.lastIndexOf(')')
        return if (open in 0 until close) t.substring(open + 1, close).trim() else t
    }

    private fun firstMatchingString(el: JsonElement?, pred: (String) -> Boolean): String? {
        when (el) {
            is JsonArray -> for (child in el) firstMatchingString(child, pred)?.let { return it }
            else -> el.str()?.let { if (pred(it)) return it }
        }
        return null
    }

    private fun haversine(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = EARTH_RADIUS_M
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    private fun bearing(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val dLng = Math.toRadians(bLng - aLng)
        val y = sin(dLng) * cos(Math.toRadians(bLat))
        val x = cos(Math.toRadians(aLat)) * sin(Math.toRadians(bLat)) -
            sin(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + FULL_CIRCLE_DEG) % FULL_CIRCLE_DEG
    }

    /** Signed smallest difference a→b in degrees, in [-180, 180]. */
    private fun angleDelta(a: Double, b: Double): Double {
        var d = (b - a + HALF_CIRCLE_DEG * 3) % FULL_CIRCLE_DEG - HALF_CIRCLE_DEG
        if (d < -HALF_CIRCLE_DEG) d += FULL_CIRCLE_DEG
        return d
    }
}
