package com.vayunmathur.maps.util

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [MapOverlay]'s manifest parsing and on-disk queries.
 *
 * The oracle is the schema `tile_build/src/manifest.rs` renders: the JSON
 * below mirrors it field for field, so a drift between publisher and client
 * fails here rather than as a silent "no update" on device.
 */
class MapOverlayTest {

    private var temp: File? = null

    @AfterTest
    fun tearDown() {
        temp?.deleteRecursively()
        temp = null
    }

    private fun root(): File {
        val dir = Files.createTempDirectory("overlay").toFile()
        temp = dir
        return dir
    }

    private fun manifestJson(seq: Long = 7): String =
        """{"version":1,"base":{"url":"https://data.maproj.org/planet4.mamaps","build_id":"0x0000000000001234","len":78017648928},"overlay_seq":$seq,"overlay_build_id":"0x000000000000abcd","segments":[{"url":"https://data.maproj.org/overlays/0007/overlay-0000.mamaps","len":11,"sha256":"abc","seq":0}],"sections":[],"min_client":1}"""

    @Test
    fun parses_the_published_schema() {
        val m = MapOverlay.parse(manifestJson())!!
        assertEquals(1, m.version)
        assertEquals("https://data.maproj.org/planet4.mamaps", m.baseUrl)
        assertEquals(0x1234L, m.baseBuildId)
        assertEquals(78017648928L, m.baseLen)
        assertEquals(7L, m.overlaySeq)
        assertEquals(0xabcdL, m.overlayBuildId)
        assertEquals(1, m.segments.size)
        assertEquals(11L, m.segments[0].len)
        assertTrue(m.sections.isEmpty())
    }

    @Test
    fun refuses_a_newer_manifest_version() {
        assertNull(MapOverlay.parse(manifestJson().replace("\"version\":1", "\"version\":99")))
    }

    @Test
    fun refuses_a_min_client_above_us() {
        assertNull(MapOverlay.parse(manifestJson().replace("\"min_client\":1", "\"min_client\":99")))
    }

    @Test
    fun refuses_garbage_quietly() {
        assertNull(MapOverlay.parse("not json"))
        assertNull(MapOverlay.parse("{}"))
    }

    @Test
    fun missing_counts_only_absent_segments() {
        val dir = root()
        val m = MapOverlay.parse(manifestJson())!!
        assertEquals(1, MapOverlay.missing(dir, m).size)
        assertEquals(11L, MapOverlay.pendingBytes(dir, m))
        assertFalse(MapOverlay.isApplied(dir, m))

        val seg = MapOverlay.segmentFile(dir, m.overlaySeq, m.segments[0])
        seg.parentFile?.mkdirs()
        seg.writeBytes(ByteArray(11))
        assertTrue(MapOverlay.missing(dir, m).isEmpty())
        assertEquals(0L, MapOverlay.pendingBytes(dir, m))
        assertTrue(MapOverlay.isApplied(dir, m))
    }

    @Test
    fun short_files_do_not_count_as_applied() {
        val dir = root()
        val m = MapOverlay.parse(manifestJson())!!
        val seg = MapOverlay.segmentFile(dir, m.overlaySeq, m.segments[0])
        seg.parentFile?.mkdirs()
        seg.writeBytes(ByteArray(5))
        assertFalse(MapOverlay.isApplied(dir, m))
        assertEquals(11L, MapOverlay.pendingBytes(dir, m))
    }

    @Test
    fun pin_round_trips_the_manifest() {
        val dir = root()
        assertNull(MapOverlay.pinned(dir))
        val text = manifestJson()
        MapOverlay.pin(dir, text)
        val pinned = MapOverlay.pinned(dir)!!
        assertEquals(7L, pinned.overlaySeq)
        assertTrue(pinned.tombstones.isEmpty())
    }

    @Test
    fun tombstones_parse_as_tile_ids() {
        val withTombs = manifestJson().replace(
            "\"sections\":[],",
            "\"sections\":[],\"tombstones\":[85,86,104],",
        )
        assertEquals(listOf(85L, 86L, 104L), MapOverlay.parse(withTombs)!!.tombstones)
    }

    @Test
    fun build_ids_parse_hex_with_prefix() {
        assertEquals(0x1234L, MapOverlay.parseBuildId("0x0000000000001234"))
        assertEquals(0L, MapOverlay.parseBuildId("garbage"))
    }

    @Test
    fun rebase_recovery_drops_completed_backup() {
        val dir = root()
        File(dir, "base.mamaps").writeBytes(ByteArray(4))
        File(dir, "base.mamaps.prev").writeBytes(ByteArray(4))
        MapOverlay.recoverRebaseCrash(dir, "base.mamaps")
        assertTrue(File(dir, "base.mamaps").isFile)
        assertFalse(File(dir, "base.mamaps.prev").exists())
    }

    @Test
    fun rebase_recovery_restores_interrupted_swap() {
        val dir = root()
        File(dir, "base.mamaps.prev").writeBytes(ByteArray(4))
        MapOverlay.recoverRebaseCrash(dir, "base.mamaps")
        assertTrue(File(dir, "base.mamaps").isFile)
        assertFalse(File(dir, "base.mamaps.prev").exists())
    }
}
