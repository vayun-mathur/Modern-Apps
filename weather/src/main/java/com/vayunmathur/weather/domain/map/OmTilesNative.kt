package com.vayunmathur.weather.domain.map

import com.vayunmathur.library.log.NativeLoader

/**
 * JNI bridge to the native Rust `.om` decoder (`libweather_om.so`, built from
 * `weather/rust/`). Loads the library once; [isAvailable] is false if the
 * native lib is missing for the current ABI so callers can degrade gracefully
 * instead of crashing.
 */
object OmTilesNative {
    // Guarded by NativeLoader: loadLibrary throws UnsatisfiedLinkError (an Error,
    // not an Exception) plus undocumented RuntimeExceptions on some ABIs.
    val isAvailable: Boolean = NativeLoader.load("weather_om", "OmTilesNative")

    /**
     * Decode [variable] from the `.om` file at [omUrl] over the bounding box
     * [west]/[south]/[east]/[north], resampling into an [outW] × [outH] raster.
     *
     * Rust fetches the bytes itself over HTTP Range (rustls; see `http_range.rs`), backed by a
     * 64KB block cache and an LRU of 12 files. Only the chunks a view covers are fetched, which
     * is what avoids the OOM the full-file `decodeRegionBytes` experiment caused.
     *
     * Blocking; call off the main thread. Returns null on any error so callers
     * degrade gracefully instead of crashing.
     */
    external fun decodeRegion(
        omUrl: String,
        variable: String,
        nx: Int,
        ny: Int,
        lonMin: Double,
        latMin: Double,
        dx: Double,
        dy: Double,
        west: Double,
        south: Double,
        east: Double,
        north: Double,
        outW: Int,
        outH: Int,
    ): FloatArray?
}
