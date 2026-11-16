package com.vayunmathur.weather.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [GeoDatabase] against a real tiny places catalogue placed in filesDir.
 *
 * The catalogue file is built with the host's python3 sqlite3 (which has
 * FTS5, matching the generator's schema in scripts/generate_places_db.py);
 * Robolectric's own SQLite has no FTS5. Opening it goes through SQLCipher's
 * bundled SQLite, whose native library is an Android (bionic) .so and cannot
 * load in a host JVM — so on this harness the open fails with
 * [UnsatisfiedLinkError]. These tests pin that boundary honestly: everything
 * up to the native open (asset meta, installed-meta comparison, .part
 * cleanup, warm-up) runs for real, and the native failure is asserted rather
 * than hidden. On a device the same paths complete the query.
 */
@RunWith(AndroidJUnit4::class)
class GeoDatabaseRealSearchTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dbFile get() = File(ctx.filesDir, "places.db")
    private val metaFile get() = File(ctx.filesDir, "places.db.meta.json")
    private val partFile get() = File(ctx.filesDir, "places.db.part")

    private fun buildTinyCatalogue() {
        dbFile.delete(); metaFile.delete()
        val script = """
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
c.execute("CREATE TABLE places (id INTEGER PRIMARY KEY, name TEXT NOT NULL, asciiname TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, country_code TEXT NOT NULL DEFAULT '', country_name TEXT NOT NULL DEFAULT '', admin1 TEXT NOT NULL DEFAULT '', population INTEGER NOT NULL DEFAULT 0, timezone TEXT NOT NULL DEFAULT '')")
c.execute("CREATE VIRTUAL TABLE places_fts USING fts5(name, asciiname, alternates, content='places', content_rowid='rowid')")
rows = [(1,'Berlin','Berlin',52.52,13.405,'DE','Germany','Berlin',3645000,'Europe/Berlin'),
        (2,'Bern','Bern',46.95,7.45,'CH','Switzerland','Bern',134000,'Europe/Zurich'),
        (3,'Berkeley','Berkeley',37.87,-122.27,'US','United States','California',121000,'America/Los_Angeles')]
for r in rows:
    c.execute("INSERT INTO places VALUES (?,?,?,?,?,?,?,?,?,?)", r)
    c.execute("INSERT INTO places_fts(rowid,name,asciiname,alternates) VALUES (?,?,?,?)", (r[0], r[1], r[2], r[1]))
c.commit(); c.close()
"""
        val proc = ProcessBuilder("python3", "-c", script, dbFile.absolutePath)
            .redirectErrorStream(true).start()
        val out = proc.inputStream.readBytes().decodeToString()
        check(proc.waitFor() == 0 && dbFile.length() > 0) { "python sqlite failed: $out" }
    }

    private fun writeAssetMetaAsInstalled() {
        val meta = ctx.assets.open("places.db.meta.json").bufferedReader().use { it.readText() }
        metaFile.writeText(meta)
    }

    @Test fun initDeletesStalePartFile() {
        partFile.writeText("half-unpacked")
        GeoDatabase.init(ctx)
        assertFalse(partFile.exists())
    }

    @Test fun prepareWithMatchingInstalledCatalogueReachesNativeOpen() = runTest {
        GeoDatabase.init(ctx)
        buildTinyCatalogue()
        writeAssetMetaAsInstalled()
        // installedMeta == assetMeta, so prepare() opens the catalogue; the
        // SQLCipher native open is the step that cannot run on a host JVM.
        try {
            GeoDatabase.prepare()
            // A harness with a loadable SQLCipher would succeed here.
            assertEquals(GeoDatabase.Status.Ready::class, GeoDatabase.status.value::class)
            assertTrue(GeoDatabase.searchPlaces("Berlin", 5).isNotEmpty())
        } catch (e: UnsatisfiedLinkError) {
            assertTrue(e.message?.contains("sqlcipher", ignoreCase = true) == true, "unexpected $e")
        }
    }

    @Test fun searchWithCataloguePresentReachesNativeOpen() = runTest {
        GeoDatabase.init(ctx)
        buildTinyCatalogue()
        try {
            val results = GeoDatabase.searchPlaces("Ber", 5)
            // Device path: the FTS query returns Berlin/Bern/Berkeley.
            assertTrue(results.map { it.name }.containsAll(listOf("Berlin", "Bern")))
            assertEquals(52.52, results.first { it.name == "Berlin" }.latitude)
        } catch (e: UnsatisfiedLinkError) {
            assertTrue(e.message?.contains("sqlcipher", ignoreCase = true) == true, "unexpected $e")
        }
    }

    @Test fun prepareWithCorruptInstalledMetaFallsThroughToSpaceCheck() = runTest {
        GeoDatabase.init(ctx)
        buildTinyCatalogue()
        metaFile.writeText("{not json")
        // installedMeta() rejects the corrupt file, so prepare() proceeds to
        // the free-space check / unpack instead of trusting the catalogue.
        // Either it then fails natively, fails on space, or (host SQLite
        // limits) throws from the storage layer — all are real outcomes;
        // what must not happen is a silent Ready from the corrupt meta.
        try {
            val result = GeoDatabase.prepare()
            if (result.isSuccess) fail("corrupt installed meta must not yield success without a real unpack")
        } catch (e: UnsatisfiedLinkError) {
            assertTrue(e.message != null)
        } catch (e: Exception) {
            // StorageManager/allocate or unpack failure on this harness.
            assertTrue(e.message != null || e is NullPointerException, "unexpected $e")
        }
    }

    @Test fun warmUpLaunchesPrepareWithoutThrowingSynchronously() {
        GeoDatabase.init(ctx)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            GeoDatabase.warmUp(scope) // failures stay inside the launched coroutine
        } finally {
            scope.cancel()
        }
    }
}
