package com.vayunmathur.maps.util

/**
 * Where the map archives live: the streamed protomaps ones, and the `.mamaps` basemap
 * that is now fetched once onto the device instead.
 *
 * Only the URLs and the on-disk location: the byte-range fetching and disk cache for the
 * streamed archives are inside `:library:map`'s renderer, which owns its own cache directory.
 */
object MapTileCache {
    /**
     * The `.mamaps` basemap, fetched once into external files alongside the graph and POI
     * packs rather than range-requested for the life of the install.
     *
     * Named for its role rather than its extent, so the same install can be handed a
     * state-sized archive today and a planet one later without the filename becoming a lie.
     */
    const val BASEMAP_ARCHIVE_FILE = "basemap.mamaps"

    /**
     * Where [BASEMAP_ARCHIVE_FILE] is fetched from on a first launch that has no copy yet.
     *
     * planet5: the dictionary-compressed format (v10 header). Installs that already hold a
     * planet4 base keep reading it (the reader speaks both) until the planet5 manifest's
     * rebase signal swaps it (see [BASEMAP_MANIFEST_URL]); older app versions keep fetching
     * planet4, which stays published unchanged.
     */
    const val BASEMAP_ARCHIVE_URL =
        "https://data.maproj.org/planet5.mamaps"

    /**
     * Where the incremental-update manifest lives (see `util/MapOverlay`). planet5's manifest
     * pins the planet5 base with an empty overlay list, which is the rebase signal: an install
     * on planet4 downloads planet5 once (on an unmetered network) and swaps it in.
     */
    const val BASEMAP_MANIFEST_URL =
        "https://data.maproj.org/planet5.manifest.json"

    /**
     * The single source of truth for the streamed basemap PMTiles URL.
     *
     * Base schema ONLY — the Protomaps layers. Our own overlays live in
     * [OVERLAY_PMTILES_URL], a separate archive, because at planet scale the base is
     * ~127 GB and the overlays are a couple of GB: joining them would make the merge
     * impossible for the sake of a file 97% of which never changes.
     * See `scripts/maps/build_v5_pmtiles.sh --no-base`.
     */
    const val BASEMAP_PMTILES_URL =
        "pmtiles://https://data.maproj.org/v4.pmtiles"

    /**
     * Our overlay archive: `safety`, `roads`, `transit_lines`, `ma_pois`,
     * `transit_stops` and the three `admin_*` levels, with no base layers.
     *
     * Planet-wide: 36.8 M tiles over z0-16, built by
     * `build_v5_pmtiles.sh --no-base` and streamed by range request like the base.
     */
    const val OVERLAY_PMTILES_URL =
        "pmtiles://https://data.maproj.org/v5-overlay.pmtiles"
}
