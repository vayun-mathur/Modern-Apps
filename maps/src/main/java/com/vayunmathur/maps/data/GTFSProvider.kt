package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter

import android.content.Context
import com.vayunmathur.library.log.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Last-resort route-colour lookup from a GTFS `routes.txt`.
 *
 * Pack-first: [OfflineRouterRouteBuilder] prefers the `route_color` baked into
 * the transit pack and only falls back here. The lookup opens
 * `assets/<feedName>/routes.txt` from the APK; no feed assets ship anymore
 * (the SLO-only `US-CA-SLOT/` folder and the orphan `world_map.png` were
 * deleted — the world pack covers San Francisco and the sidecars never did),
 * so this returns null when absent rather than failing. API unchanged: the
 * CSV parser is covered by `GTFSProviderTest`.
 */
object GTFSProvider {
    // Accessed concurrently from OfflineRouter.getRoute (Dispatchers.Default)
    // and from the map layers on the Main thread, so use a thread-safe map —
    // a plain HashMap mutation race can corrupt internal buckets and cause
    // ConcurrentModificationException or infinite get() loops.
    private val routeColors = ConcurrentHashMap<String, String>() // Key: feedName:routeName, Value: #HEX

    fun getRouteColor(context: Context, feedName: String, routeName: String): String? {
        val cacheKey = "$feedName:$routeName"
        if (routeColors.containsKey(cacheKey)) return routeColors[cacheKey]

        return try {
            findRouteColor(context, feedName, routeName)?.also { routeColors[cacheKey] = it }
        } catch (e: java.io.IOException) {
            Log.status("GTFSProvider", "Failed to read routes.txt for $feedName", e)
            null
        }
    }

    private fun findRouteColor(context: Context, feedName: String, routeName: String): String? {
        val assetPath = "$feedName/routes.txt"
        context.assets.open(assetPath).use { inputStream ->
            val reader = inputStream.bufferedReader()
            val header = parseCsvLine(reader.readLine() ?: return null).map { it.trim() }
            val columns = routeColumns(header) ?: return null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                rowColor(parseCsvLine(line ?: continue), columns, routeName)?.let { return it }
            }
        }
        return null
    }

    private data class RouteColumns(val shortNameIdx: Int, val longNameIdx: Int, val colorIdx: Int)

    private fun routeColumns(header: List<String>): RouteColumns? {
        val colorIdx = header.indexOf("route_color")
        if (colorIdx == -1) return null
        return RouteColumns(
            shortNameIdx = header.indexOf("route_short_name"),
            longNameIdx = header.indexOf("route_long_name"),
            colorIdx = colorIdx,
        )
    }

    private fun rowColor(parts: List<String>, columns: RouteColumns, routeName: String): String? {
        val shortName = parts.getOrNull(columns.shortNameIdx)
        val longName = parts.getOrNull(columns.longNameIdx)
        if (shortName != routeName && longName != routeName) return null
        return parts.getOrNull(columns.colorIdx)?.takeIf { it.isNotEmpty() }?.let { "#$it" }
    }

    /**
     * Split one RFC 4180 CSV record into fields, honouring double-quoted fields
     * that contain commas (GTFS routinely quotes `route_long_name`, e.g.
     * `"Judah, Ocean Beach"`) and `""` escapes. A leading UTF-8 BOM is stripped.
     * Records with embedded newlines are not supported — this is line-oriented,
     * which is fine for the short rows in `routes.txt`.
     */
    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        if (line.startsWith('\uFEFF')) i = 1
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes ->
                        when {
                            c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                                current.append('"')
                                i++
                            }
                            c == '"' -> inQuotes = false
                            else -> current.append(c)
                        }
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields.add(current.toString())
                    current.setLength(0)
                }
                c == '\r' -> {} // trailing CR from a CRLF file
                else -> current.append(c)
            }
            i++
        }
        fields.add(current.toString())
        return fields
    }
}
