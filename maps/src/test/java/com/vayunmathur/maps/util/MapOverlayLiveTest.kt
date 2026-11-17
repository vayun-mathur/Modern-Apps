package com.vayunmathur.maps.util
import com.vayunmathur.maps.data.MapOverlay
import com.vayunmathur.maps.data.MapTileCache

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The `planet5.manifest.json` shape through [MapOverlay.parse], checked
 * against what the app downloads ([MapTileCache.BASEMAP_ARCHIVE_URL]) and
 * what a planet4 install will do with it.
 *
 * By default this parses a captured copy of the published manifest (same
 * build id, length and empty overlay stack), so it runs hermetically in CI
 * instead of skipping. With `MAMAPS_LIVE=1` it additionally fetches the
 * live manifest and requires it to still match the pinned planet5 build.
 */
class MapOverlayLiveTest {

    @Test
    fun `live planet5 manifest parses and pins the archive the app downloads`() {
        val live = System.getenv("MAMAPS_LIVE") == "1"
        val text = if (live) {
            URI(MapOverlay.MANIFEST_URL).toURL().readText()
        } else {
            CAPTURED_MANIFEST
        }
        println("manifest (${text.length} B): $text")
        val m = assertNotNull(MapOverlay.parse(text), "the client refuses the planet5 manifest")
        assertEquals(MapTileCache.BASEMAP_MANIFEST_URL, MapOverlay.MANIFEST_URL)
        assertEquals(MapTileCache.BASEMAP_ARCHIVE_URL, m.baseUrl)
        assertEquals(PLANET5_BUILD_ID, m.baseBuildId)
        assertEquals(PLANET5_LEN, m.baseLen)
        // A fresh base with an empty stack: the rebase signal. A planet4 install (no pinned
        // manifest, or one pinned to planet4's build) downloads planet5 once in the
        // background and swaps it in; a fresh install fetches planet5 at the startup gate.
        assertTrue(m.segments.isEmpty(), "a rebase manifest carries no segments")
        assertEquals(0L, m.overlaySeq)
        assertTrue(m.tombstones.isEmpty())
    }

    private companion object {
        // The published ~50 GB planet5 of 2026-11-14 (planet_build.sh start to finish at
        // a5ba63230: header v11 superblocks, v12 bodies, graph v9). Replaces the 63 GB build.
        const val PLANET5_BUILD_ID = -0x768935f835da84c7L // 0x8976ca07ca257b39 as a signed Long
        const val PLANET5_LEN = 49_990_890_888L

        /** Captured shape of the published manifest (build id as unsigned hex, as served). */
        const val CAPTURED_MANIFEST = """
            {
              "version": 1, "min_client": 1, "overlay_seq": 0,
              "overlay_build_id": "0x8976ca07ca257b39",
              "base": {
                "url": "https://data.maproj.org/planet5.mamaps",
                "build_id": "0x8976ca07ca257b39",
                "len": 49990890888
              },
              "segments": [],
              "tombstones": []
            }
        """
    }
}
