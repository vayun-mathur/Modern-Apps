package com.vayunmathur.maps.util

import android.content.Context
import com.vayunmathur.library.map.MoonTextures
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the Moon raster pair from the bundled assets into [MoonTextures].
 *
 * The assets (`assets/moon/moon_color_4096.rgba` + `moon_dem_2048.rg`,
 * converted offline from NASA SVS 4720 by `analysis/convert_moon.py`) total
 * ~37MB, so this runs on IO and only on Moon select — Earth sessions never pay
 * the read. Nothing is cached here: the caller holds the result only while the
 * Moon is shown, and a re-select reads the assets again.
 *
 * Null when the assets are missing or unreadable: the Moon frame then draws
 * the clear colour rather than crashing (see the native `record_moon`).
 */
object MoonAssetLoader {
    private const val COLOR_ASSET = "moon/moon_color_4096.rgba"
    private const val COLOR_W = 4096
    private const val COLOR_H = 2048
    private const val COLOR_BYTES_PER_PX = 4
    private const val DEM_BYTES_PER_PX = 2
    private const val DEM_ASSET = "moon/moon_dem_2048.rg"
    private const val DEM_W = 2048
    private const val DEM_H = 1024

    suspend fun load(context: Context): MoonTextures? = withContext(Dispatchers.IO) {
        try {
            val color = context.assets.open(COLOR_ASSET).use { it.readBytes() }
            val dem = context.assets.open(DEM_ASSET).use { it.readBytes() }
            if (color.size != COLOR_W * COLOR_H * COLOR_BYTES_PER_PX ||
                dem.size != DEM_W * DEM_H * DEM_BYTES_PER_PX
            ) {
                return@withContext null
            }
            MoonTextures(color, COLOR_W, COLOR_H, dem, DEM_W, DEM_H)
        } catch (_: IOException) {
            null
        }
    }
}
