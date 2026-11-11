package com.vayunmathur.maps.util

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The LIVE `planet4.manifest.json` through [MapOverlay.parse], checked against what the app
 * downloads ([MapTileCache.BASEMAP_ARCHIVE_URL]) and what a planet3 install will do with it.
 * Skipped unless `MAMAPS_LIVE=1` (network to data.maproj.org).
 */
class MapOverlayLiveTest {

    @Test
    fun `live planet4 manifest parses and pins the archive the app downloads`() {
        Assume.assumeTrue("MAMAPS_LIVE not set; live test skipped", System.getenv("MAMAPS_LIVE") == "1")
        val text = URI(MapOverlay.MANIFEST_URL).toURL().readText()
        println("live manifest (${text.length} B): $text")
        val m = assertNotNull(MapOverlay.parse(text), "the client refuses the live manifest")
        assertEquals(MapTileCache.BASEMAP_MANIFEST_URL, MapOverlay.MANIFEST_URL)
        assertEquals(MapTileCache.BASEMAP_ARCHIVE_URL, m.baseUrl)
        assertEquals(PLANET4_BUILD_ID, m.baseBuildId)
        assertEquals(PLANET4_LEN, m.baseLen)
        // A fresh base with an empty stack: the rebase signal. A planet3 install (no pinned
        // manifest, or one pinned to planet3's build) downloads planet4 once in the
        // background and swaps it in; a fresh install fetches planet4 at the startup gate.
        assertTrue(m.segments.isEmpty(), "a rebase manifest carries no segments")
        assertEquals(0L, m.overlaySeq)
        assertTrue(m.tombstones.isEmpty())
    }

    private companion object {
        const val PLANET4_BUILD_ID = 0x2ab5ba5833e54438L
        const val PLANET4_LEN = 81_331_811_296L
    }
}
