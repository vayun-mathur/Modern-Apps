package com.vayunmathur.maps.util

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * The LIVE `planet5.manifest.json` through [MapOverlay.parse], checked against what the app
 * downloads ([MapTileCache.BASEMAP_ARCHIVE_URL]) and what a planet4 install will do with it.
 * Skipped unless `MAMAPS_LIVE=1` (network to data.maproj.org).
 */
class MapOverlayLiveTest {

    @Test
    fun `live planet5 manifest parses and pins the archive the app downloads`() {
        Assume.assumeTrue("MAMAPS_LIVE not set; live test skipped", System.getenv("MAMAPS_LIVE") == "1")
        val text = URI(MapOverlay.MANIFEST_URL).toURL().readText()
        println("live manifest (${text.length} B): $text")
        val m = assertNotNull(MapOverlay.parse(text), "the client refuses the live manifest")
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
        // The planet5 build of 2026-11-14 (planet_build.sh at a465eb7aa).
        const val PLANET5_BUILD_ID = -0x420c77ce6b6280d4L // 0xbdf38831949d7f2c as a signed Long
        const val PLANET5_LEN = 64_211_565_112L
    }
}
