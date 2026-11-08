package com.vayunmathur.maps.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the permanently-empty place index: [PoiIndex.initialize]
 * used to fail once and never retry, so a call that ran before the POI files
 * finished downloading (e.g. the cold-start intent path, which runs before the
 * `.mamaps` gate) poisoned every later call — search stayed empty until the
 * process died. `initialize` now re-maps when the on-disk files changed since
 * the last attempt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PoiIndexRetryTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        resetPoiIndex()
        cleanExtDir()
    }

    @AfterTest
    fun tearDown() {
        cleanExtDir()
        resetPoiIndex()
    }

    @Test
    fun initializeRecoversAfterFilesArrive() {
        // First-launch state: the archive has not downloaded yet.
        assertFalse(PoiIndex.initialize(context))
        assertFalse(PoiIndex.available)

        // The download lands after the first attempt.
        writeSideFiles(
            extDir(),
            listOf(
                Triple("Cafe Alpha", 340500000, -1182440000),
                Triple("Beta Bakery", 340600000, -1182500000),
            ),
        )

        // Must recover without a process restart or an explicit reload call.
        assertTrue(PoiIndex.initialize(context))
        assertTrue(PoiIndex.available)
        val hits = PoiIndex.searchByName("cafe", 34.05, -118.244, 20)
        assertEquals(listOf("Cafe Alpha"), hits.map { it.name })
    }

    @Test
    fun initializeDoesNotRetryWhenNothingChanged() {
        assertFalse(PoiIndex.initialize(context))
        // Unchanged files: the failure stands, with no repeated mmap attempts.
        assertFalse(PoiIndex.initialize(context))
        assertFalse(PoiIndex.available)
    }

    private fun extDir(): File = context.getExternalFilesDir(null)!!

    private fun cleanExtDir() {
        extDir().listFiles()?.forEach {
            if (it.name == PoiIndex.INDEX_FILE || it.name == PoiIndex.NAMES_FILE ||
                it.name == MapTileCache.BASEMAP_ARCHIVE_FILE
            ) {
                it.delete()
            }
        }
    }

    /** Reset the singleton to fresh-process state; tolerates fields the fix adds. */
    private fun resetPoiIndex() {
        val fields = PoiIndex::class.java.declaredFields.associateBy { it.name }
        fields["mapped"]?.let { it.isAccessible = true; it.set(PoiIndex, null) }
        fields["tried"]?.let { it.isAccessible = true; it.setBoolean(PoiIndex, false) }
        fields["lastFingerprint"]?.let { it.isAccessible = true; it.set(PoiIndex, null) }
    }

    /**
     * Minimal `poi_index.bin` + `poi_names.bin` fixture (no side files, so the
     * transcribed-scan search path answers), mirroring `PoiIndexTest.load`.
     */
    private fun writeSideFiles(dir: File, pois: List<Triple<String, Int, Int>>) {
        val sorted = pois.sortedWith { a, b ->
            java.lang.Long.compareUnsigned(
                spatialFromE7(a.second, a.third),
                spatialFromE7(b.second, b.third),
            )
        }
        val nameOff = LinkedHashMap<String, Int>()
        val namesOut = ByteArrayOutputStream()
        for ((name, _, _) in sorted) {
            if (name !in nameOff) {
                nameOff[name] = namesOut.size()
                namesOut.write(name.toByteArray(Charsets.UTF_8))
                namesOut.write(0)
            }
        }
        File(dir, PoiIndex.NAMES_FILE).writeBytes(namesOut.toByteArray())

        val index = ByteBuffer.allocate(sorted.size * 14).order(ByteOrder.LITTLE_ENDIAN)
        for ((name, latE7, lonE7) in sorted) {
            index.putInt(latE7)
            index.putInt(lonE7)
            index.putInt(nameOff.getValue(name))
            index.putShort(0)
        }
        File(dir, PoiIndex.INDEX_FILE).writeBytes(index.array())
    }
}
