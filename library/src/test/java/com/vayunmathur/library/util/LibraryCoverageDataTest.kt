package com.vayunmathur.library.util

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DataStore remaining branches, LocalizedDateNames locales, BackupHelper edges, Util helpers. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LibraryCoverageDataTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun newStore(): DataStoreUtils {
        val dir = Files.createTempDirectory("ds-cov").toFile()
        return DataStoreUtils(PreferenceDataStoreFactory.create { File(dir, "t.preferences_pb") })
    }

    // ---- DataStore ----

    @Test fun dataStoreSyncGettersAndOnlyIfAbsentVariants() = runBlocking {
        val store = newStore()
        store.setByteArray("blob", byteArrayOf(9, 8))
        assertTrue(byteArrayOf(9, 8).contentEquals(store.getByteArray("blob")!!))
        store.setByteArray("blob", byteArrayOf(1), onlyIfAbsent = true)
        assertTrue(byteArrayOf(9, 8).contentEquals(store.getByteArrayAwait("blob")!!))
        store.setByteArray("blob", byteArrayOf(7), onlyIfAbsent = false)
        assertTrue(byteArrayOf(7).contentEquals(store.getByteArrayAwait("blob")!!))

        assertNull(store.getLong("nope"))
        store.setLong("n", 5, onlyIfAbsent = true)
        store.setLong("n", 6, onlyIfAbsent = true)
        assertEquals(5L, store.getLong("n"))
        assertEquals(5L, store.getLongAwait("n"))

        assertNull(store.getDouble("nope"))
        store.setDouble("d", 1.25)
        assertEquals(1.25, store.getDouble("d"))

        assertFalse(store.getBoolean("flag", false))
        assertTrue(store.getBoolean("flag", true))
        store.setBoolean("flag", true)
        assertTrue(store.getBoolean("flag", false))
        assertTrue(store.getBooleanAwait("flag", false))
        assertFalse(store.getBooleanAwait("absent", false))
        assertTrue(store.getBooleanAwait("absent", true))
    }

    @Test fun dataStoreFlowsWithAndWithoutDefaults() = runBlocking {
        val store = newStore()
        assertEquals(99L, store.longFlow("missing", 99L).first())
        assertEquals(true, store.booleanFlow("missing", true).first())
        assertEquals(emptySet(), store.stringSetFlow("missing").first())
        store.setLong("count", 3)
        assertEquals(3L, store.longFlow("count").first())
        assertEquals(3L, store.longFlow("count", 0L).first())
        store.setBoolean("on", true)
        assertEquals(true, store.booleanFlow("on").first())
        assertEquals(true, store.booleanFlow("on", false).first())
        store.setString("s", "hello")
        assertEquals("hello", store.stringFlow("s").first())
        store.setDouble("ratio", 0.5)
        assertEquals(0.5, store.doubleFlow("ratio").first())
    }

    @Test fun dataStoreSetOpsAwaitAndRemoveKeysAllTypes() = runBlocking {
        val store = newStore()
        assertTrue(store.addStringToSetIfAbsent("tags", "x"))
        store.removeStringFromSetAwait("tags", "x")
        assertEquals(emptySet(), store.getStringSetAwait("tags"))
        // Fire-and-forget add/remove eventually land.
        store.addStringToSet("tags", "y")
        withTimeout(5_000) { while (!store.getStringSetAwait("tags").contains("y")) delay(20) }
        store.removeStringFromSet("tags", "y")
        withTimeout(5_000) { while (store.getStringSetAwait("tags").isNotEmpty()) delay(20) }

        // removeKeys removes every type stored under a name.
        store.setString("k", "v"); store.setLong("k2", 1); store.setDouble("k3", 2.0)
        store.setBoolean("k4", true); store.setByteArray("k5", byteArrayOf(1))
        store.addStringToSetIfAbsent("k6", "a")
        store.removeKeys(listOf("k", "k2", "k3", "k4", "k5", "k6"))
        assertNull(store.getStringAwait("k"))
        assertNull(store.getLongAwait("k2"))
        assertNull(store.getDouble("k3"))
        assertEquals(false, store.getBooleanAwait("k4", false))
        assertNull(store.getByteArrayAwait("k5"))
        assertEquals(emptySet(), store.getStringSetAwait("k6"))

        // updateString from absent treats current as null.
        store.updateString("fresh") { (it ?: "seed") + "!" }
        assertEquals("seed!", store.getStringAwait("fresh"))
        // setLongIfGreater on absent key: any positive value wins over implicit 0.
        assertTrue(store.setLongIfGreater("high", 10))
        assertFalse(store.setLongIfGreater("high", 10))
        assertTrue(store.setLongIfGreater("high", 11))
    }

    @Test fun dataStoreCopyFromOverwriteAndEmptySourceBranches() = runBlocking {
        val src = newStore(); val dst = newStore()
        src.setString("a", "1"); src.setLong("b", 2)
        assertEquals(0, dst.copyFrom(src, emptyList(), overwrite = false))
        assertEquals(0, dst.copyFrom(newStore(), listOf("a"), overwrite = false))
        assertEquals(2, dst.copyFrom(src, listOf("a", "b", "absent"), overwrite = false))
        assertEquals("1", dst.getStringAwait("a"))
        src.setString("a", "changed")
        assertEquals(0, dst.copyFrom(src, listOf("a"), overwrite = false))
        assertEquals(1, dst.copyFrom(src, listOf("a"), overwrite = true))
        assertEquals("changed", dst.getStringAwait("a"))
    }

    // ---- LocalizedDateNames ----

    @Test fun localizedNamesAcrossLocalesAndStyles() {
        val usMonths = localizedMonthNames(DateNameStyle.FULL, Locale.US)
        assertEquals(12, usMonths.size)
        assertEquals("January", usMonths[0]); assertEquals("December", usMonths[11])
        assertEquals("Jan", localizedMonthNames(DateNameStyle.SHORT, Locale.US)[0])
        assertEquals(12, localizedMonthNames(DateNameStyle.NARROW, Locale.US).size)
        val fr = localizedMonthNames(DateNameStyle.FULL, Locale.FRANCE)
        assertTrue(fr[0].lowercase().startsWith("janv"), fr.toString())
        val deDays = localizedDayOfWeekNames(DateNameStyle.FULL, Locale.GERMANY)
        assertEquals(7, deDays.size)
        assertTrue(deDays[0].startsWith("Montag"), deDays.toString()) // ISO Monday first
        assertEquals("Monday", localizedDayOfWeekNames(DateNameStyle.FULL, Locale.US)[0])
        assertEquals("Sunday", localizedDayOfWeekNames(DateNameStyle.FULL, Locale.US)[6])
        assertEquals("Mon", localizedDayOfWeekNames(DateNameStyle.SHORT, Locale.US)[0])
        assertEquals(7, localizedDayOfWeekNames(DateNameStyle.NARROW, Locale.JAPAN).size)
    }

    @Test fun firstDayOfWeekAndWeekRotationPerLocale() {
        assertEquals(7, localeFirstDayOfWeek(Locale.US))      // Sunday
        assertEquals(1, localeFirstDayOfWeek(Locale.GERMANY)) // Monday
        assertEquals(1, localeFirstDayOfWeek(Locale.FRANCE))
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), localeWeekDayNumbers(Locale.US))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), localeWeekDayNumbers(Locale.GERMANY))
        // Every rotation is a permutation of 1..7 starting at the first day.
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.JAPAN, Locale.UK, Locale.CANADA)) {
            val week = localeWeekDayNumbers(locale)
            assertEquals((1..7).toList(), week.sorted(), "$locale")
            assertEquals(localeFirstDayOfWeek(locale), week.first(), "$locale")
        }
    }

    @Test fun amPmMarkersAndFormatBuilderBothCases() {
        assertEquals("AM" to "PM", localizedAmPmMarkers(Locale.US))
        val ja = localizedAmPmMarkers(Locale.JAPAN)
        assertTrue(ja.first.isNotBlank() && ja.first != ja.second)
        val morning = kotlinx.datetime.LocalTime.Format { localizedAmPmMarker(Locale.US) }
            .format(kotlinx.datetime.LocalTime(9, 5))
        assertTrue(morning.contains("AM"), morning)
        val eveningLower = kotlinx.datetime.LocalTime.Format { localizedAmPmMarker(Locale.US, lowercase = true) }
            .format(kotlinx.datetime.LocalTime(21, 5))
        assertTrue(eveningLower.contains("pm"), eveningLower)
        // DateNameStyle widths are distinct.
        assertTrue(DateNameStyle.SHORT.icuWidth != DateNameStyle.FULL.icuWidth)
        assertTrue(DateNameStyle.NARROW.icuWidth != DateNameStyle.FULL.icuWidth)
    }

    // ---- BackupHelper edges ----

    private class EmptyCodec : DbBackupCodec {
        val imports = mutableListOf<String>()
        override fun exportDatabase(context: Context, dbName: String, password: String, outputFile: File) {
            outputFile.writeBytes(ByteArray(0)) // empty export must be skipped
        }
        override fun importDatabase(context: Context, dbName: String, password: String, inputFile: File) {
            imports += dbName
        }
    }

    @Test fun backupSkipsEmptyCodecExportAndMissingDbOnRestore() {
        val codec = EmptyCodec()
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, listOf("db1" to "pw"), emptyList(), emptyList(), emptyList(), out, codec)
        val target = Files.createTempDirectory("emptycodec").toFile()
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
        assertFalse(File(target, "db1.db").exists())
        // Restore of that zip: db file absent, so import is never called.
        BackupHelper.performFullRestore(ctx, listOf("db1" to "pw"), emptyList(), emptyList(), emptyMap(), ByteArrayInputStream(out.toByteArray()), codec)
        assertTrue(codec.imports.isEmpty())
    }

    @Test fun backupLegacyDatastorePathAndSingleFileZip() {
        // Legacy layout: filesDir/<name>.preferences_pb (no datastore/ subdir).
        val legacy = File(ctx.filesDir, "legacy.preferences_pb").apply { writeBytes(byteArrayOf(4, 5)) }
        try {
            val out = ByteArrayOutputStream()
            BackupHelper.performFullBackup(ctx, emptyList(), listOf("legacy"), emptyList(), emptyList(), out)
            legacy.delete()
            BackupHelper.performFullRestore(ctx, emptyList(), listOf("legacy"), emptyList(), emptyMap(), ByteArrayInputStream(out.toByteArray()))
            // Named (non-default) stores restore under datastore/, regardless of legacy source.
            assertTrue(byteArrayOf(4, 5).contentEquals(File(ctx.filesDir, "datastore/legacy.preferences_pb").readBytes()))
        } finally { legacy.delete(); File(ctx.filesDir, "datastore/legacy.preferences_pb").delete() }

        val single = File(ctx.cacheDir, "single.txt").apply { writeText("solo") }
        val out2 = ByteArrayOutputStream()
        BackupHelper.zipFiles(listOf(single), ctx.cacheDir, out2)
        val target = Files.createTempDirectory("singleout").toFile()
        BackupHelper.unzipFiles(ByteArrayInputStream(out2.toByteArray()), target)
        assertEquals("solo", File(target, "single.txt").readText())
    }

    @Test fun backupWithNothingToBackupStillProducesReadableZip() {
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, emptyList(), listOf("ghostds"), listOf("ghostpref"), listOf(File(ctx.cacheDir, "nope.bin")), out)
        assertTrue(out.size() > 0)
        val target = Files.createTempDirectory("nothingout").toFile()
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
    }

    @Test fun restoreExtraFileOverwritesExistingDirectory() {
        val src = File(ctx.cacheDir, "thing.txt").apply { writeText("file-body") }
        val out = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, emptyList(), emptyList(), emptyList(), listOf(src), out)
        val dest = File(ctx.cacheDir, "thing.txt")
        // Replace the file with a directory of the same name; restore must clear it.
        dest.delete(); dest.mkdirs(); File(dest, "stale.txt").writeText("stale")
        BackupHelper.performFullRestore(ctx, emptyList(), emptyList(), emptyList(), mapOf("thing.txt" to dest), ByteArrayInputStream(out.toByteArray()))
        assertTrue(dest.isFile)
        assertEquals("file-body", dest.readText())
    }

    // ---- Util helpers ----

    @Test fun utilHelpersEdgeCases() {
        assertEquals(42, tryOrDefault(42) { 42 })
        assertEquals(42, tryOrDefault(42) { throw IllegalStateException("x") })
        assertEquals(1.23, 1.2345.round(2))
        assertEquals(1.234, 1.2345.round(3)) // binary float: 1.2345*1000 is just under 1234.5
        assertEquals(-2.0, (-1.5).round(0))
        assertEquals(100.0, 99.99.round(1))
        assertEquals("Hello", "hello".firstLetterUppercase())
        assertEquals("Hello", "Hello".firstLetterUppercase())
        assertEquals("", "".firstLetterUppercase())
        assertEquals("123", "123".firstLetterUppercase())
        val tuple = Tuple4("a", 1, true, 2.5)
        assertEquals("a", tuple.first); assertEquals(1, tuple.second)
        assertEquals(true, tuple.third); assertEquals(2.5, tuple.fourth)
        assertEquals(Tuple4("a", 1, true, 2.5), tuple)
        val linesFile = Files.createTempFile("lines", ".txt").toFile().apply { writeText("one\ntwo\nthree") }
        linesFile.inputStream().use { input -> assertEquals(listOf("one", "two", "three"), input.readLines()) }
    }
}
