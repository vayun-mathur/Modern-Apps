package com.vayunmathur.library.map

/**
 * Which pan/zoom/rotate/tilt gestures are enabled. Tilt is the two-finger vertical drag;
 * rotation needs no separate detector, so it is one flag here.
 */
data class GestureOptions(
    val isScrollEnabled: Boolean = true,
    val isZoomEnabled: Boolean = true,
    val isRotateEnabled: Boolean = true,
    val isTiltEnabled: Boolean = true,
) {
    companion object {
        /** Pan + zoom, rotation locked north-up (the old default behaviour). */
        val RotationLocked =
            GestureOptions(isScrollEnabled = true, isZoomEnabled = true, isRotateEnabled = false)

        /** Pan + zoom only: no twist-rotate, no two-finger tilt. What every app but `maps` uses. */
        val TiltLocked =
            GestureOptions(
                isScrollEnabled = true,
                isZoomEnabled = true,
                isRotateEnabled = false,
                isTiltEnabled = false
            )

        /** All gestures disabled (static map). */
        val AllDisabled =
            GestureOptions(
                isScrollEnabled = false,
                isZoomEnabled = false,
                isRotateEnabled = false,
                isTiltEnabled = false,
            )
    }
}

/** True when no transform gesture (scroll/zoom/rotate) is enabled. */
internal val GestureOptions.isTransformDisabled: Boolean
    get() = !isScrollEnabled && !isZoomEnabled && !isRotateEnabled

/** True when the tilt detector has nothing to disambiguate against. */
internal val GestureOptions.isTiltDisabled: Boolean
    get() = (!isScrollEnabled && !isZoomEnabled) || !isTiltEnabled

/**
 * Map ornaments.
 *
 * Attribution used to live here as an ornament, but task 51 (#9) removed the overlay from
 * `VectorMap` outright: the ODbL credit the map drew belongs on a host About/Legal screen
 * instead (see `ATTRIBUTION` in `VectorMap.kt` — no app shows it elsewhere yet, so the user
 * must place it). The flag is kept because it is part of the options object's shape; it
 * currently governs nothing, since attribution was the only ornament ever implemented.
 */
data class OrnamentOptions(
    val isAttributionEnabled: Boolean = true,
) {
    companion object {
        val AllDisabled = OrnamentOptions(isAttributionEnabled = false)
    }
}

data class MapOptions(
    val gestureOptions: GestureOptions = GestureOptions(),
    val ornamentOptions: OrnamentOptions = OrnamentOptions(),
    /**
     * Which optional layers (POI, transit) to draw. Both off by default — see
     * [LayerOptions] for why, and for what turning one on costs.
     */
    val layerOptions: LayerOptions = LayerOptions(),
    /**
     * Where tiles come from. [TileSource.Server] (default) streams range requests for
     * `planet5.mamaps` over HTTP with a disk range cache; [TileSource.LocalFile] reads a
     * pushed `.mamaps` archive from the app's external files dir and does no networking.
     * Today only `maps` pushes an archive, so every other app stays on the default.
     */
    val tileSource: TileSource = TileSource.Server,
)

/**
 * Which planetary body the map draws.
 *
 * Earth is the vector basemap this library has always drawn. Moon is the NASA
 * SVS CGI Moon Kit raster pair (LROC color + LOLA elevation) bent onto the same
 * globe path — maps-only, session-scoped, and only meaningful while the globe
 * is active (zoomed out). Every other host keeps the default without knowing
 * this exists.
 */
enum class MapBody {
    Earth,
    Moon,
}

/**
 * Where the renderer reads its tiles from.
 */
enum class TileSource {
    /** Stream `planet5.mamaps` range requests over HTTP, cached on disk. */
    Server,
    /** Read a pushed `.mamaps` archive from external files; no networking. Falls back to
     * [Server] when no archive file is present, so a missing push degrades to streaming
     * rather than a blank map. */
    LocalFile,
}
