package com.vayunmathur.library.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.intents.calendar.EventData
import com.vayunmathur.library.intents.clock.SetAlarmData
import com.vayunmathur.library.intents.clock.SetTimerData
import com.vayunmathur.library.intents.contacts.ContactData
import com.vayunmathur.library.intents.email.EmailData
import com.vayunmathur.library.intents.email.EmailSearchQuery
import com.vayunmathur.library.intents.findfamily.FamilyMemberData
import com.vayunmathur.library.intents.fooddelivery.OrderLookupRequest
import com.vayunmathur.library.intents.fooddelivery.OrderLookupResult
import com.vayunmathur.library.intents.maps.DirectionsLatLng
import com.vayunmathur.library.intents.maps.DirectionsRequest
import com.vayunmathur.library.intents.maps.DirectionsResult
import com.vayunmathur.library.intents.maps.DirectionsSegment
import com.vayunmathur.library.intents.music.MusicSearchResult
import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.intents.notes.InsertNoteData
import com.vayunmathur.library.intents.notes.NoteData
import com.vayunmathur.library.intents.taxi.RideEstimateRequest
import com.vayunmathur.library.intents.taxi.RideEstimateResult
import com.vayunmathur.library.intents.todo.CompleteTodoData
import com.vayunmathur.library.intents.todo.GetTodosQuery
import com.vayunmathur.library.intents.todo.InsertTodoData
import com.vayunmathur.library.intents.todo.TodoData
import com.vayunmathur.library.intents.weather.WeatherData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Wave2: default-arg entry points, DTO property reads, backup/intent branches skipped by wave1. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryWave2DataTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    // ---- LocalizedDateNames: calls WITHOUT explicit locale/style defaults ----
    @Test fun localizedDefaultsAndFirstDayBranches() {
        // These invoke the $default synthetics wave1 never hit (it always passed a locale).
        assertEquals(12, localizedMonthNames(DateNameStyle.FULL).size)
        assertEquals(12, localizedMonthNames(DateNameStyle.SHORT).size)
        assertEquals(7, localizedDayOfWeekNames(DateNameStyle.FULL).size)
        assertEquals(7, localizedDayOfWeekNames(DateNameStyle.SHORT).size)
        val markers = localizedAmPmMarkers()
        assertTrue(markers.first.isNotBlank() && markers.second.isNotBlank())
        assertTrue(localeFirstDayOfWeek() in 1..7)
        assertEquals((1..7).toList(), localeWeekDayNumbers().sorted())
        assertEquals(localeFirstDayOfWeek(), localeWeekDayNumbers().first())
        val fmt = LocalTime.Format { localizedAmPmMarker() }
        assertTrue(fmt.format(LocalTime(9, 0)).isNotBlank())
        val fmtLower = LocalTime.Format { localizedAmPmMarker(lowercase = true) }
        assertTrue(fmtLower.format(LocalTime(21, 0)).isNotBlank())

        // First-day branches beyond Mon/Sun: Saturday (Egypt), Friday (Iran), Sunday, Monday.
        assertEquals(6, localeFirstDayOfWeek(Locale.Builder().setLanguage("ar").setRegion("EG").build()))
        assertTrue(localeFirstDayOfWeek(Locale.Builder().setLanguage("fa").setRegion("IR").build()) in 1..7)
        assertEquals(7, localeFirstDayOfWeek(Locale.US))
        assertEquals(1, localeFirstDayOfWeek(Locale.UK))
        for (loc in listOf(Locale.US, Locale.UK, Locale.CANADA, Locale.JAPAN,
            Locale.Builder().setLanguage("ar").setRegion("EG").build(),
            Locale.Builder().setLanguage("fa").setRegion("IR").build(),
            Locale.Builder().setLanguage("he").setRegion("IL").build())) {
            val week = localeWeekDayNumbers(loc)
            assertEquals((1..7).toList(), week.sorted(), "$loc")
            assertEquals(localeFirstDayOfWeek(loc), week.first(), "$loc")
            assertEquals(12, localizedMonthNames(DateNameStyle.NARROW, loc).size)
            assertEquals(7, localizedDayOfWeekNames(DateNameStyle.NARROW, loc).size)
        }
    }

    // ---- IPC DTOs: read every property explicitly (getters, not serializer) ----
    @Test fun dtoPropertyReads() {
        val w = WeatherData("Oslo", 3.0, 1.0, "Snow", 5.0, -1.0, 40, 80, 20.0, "NW", 1.5, 100L, 200L, "e")
        assertEquals("Oslo", w.locationName); assertEquals(3.0, w.temperatureCelsius)
        assertEquals(1.0, w.feelsLikeCelsius); assertEquals("Snow", w.condition)
        assertEquals(5.0, w.highCelsius); assertEquals(-1.0, w.lowCelsius)
        assertEquals(40, w.precipitationChancePercent); assertEquals(80, w.humidityPercent)
        assertEquals(20.0, w.windKph); assertEquals("NW", w.windDirection)
        assertEquals(1.5, w.uvIndex); assertEquals(100L, w.sunriseEpochSec)
        assertEquals(200L, w.sunsetEpochSec); assertEquals("e", w.error)
        val wDef = WeatherData(null, 0.0, 0.0, "Clear", 0.0, 0.0, 0, 0, 0.0, "N", 0.0)
        assertNull(wDef.sunriseEpochSec); assertNull(wDef.sunsetEpochSec); assertNull(wDef.error)

        val req = DirectionsRequest(1.0, 2.0, 3.0, 4.0, "walk")
        assertEquals(1.0, req.fromLat); assertEquals(2.0, req.fromLng)
        assertEquals(3.0, req.toLat); assertEquals(4.0, req.toLng); assertEquals("walk", req.mode)
        val ll = DirectionsLatLng(9.0, 8.0); assertEquals(9.0, ll.lat); assertEquals(8.0, ll.lng)
        val seg = DirectionsSegment(listOf(ll), 0.25); assertEquals(listOf(ll), seg.points); assertEquals(0.25, seg.speedRatio)
        val res = DirectionsResult(listOf(ll), listOf(seg), 12.0, 34.0)
        assertEquals(listOf(ll), res.polyline); assertEquals(listOf(seg), res.segments)
        assertEquals(12.0, res.distanceMeters); assertEquals(34.0, res.durationSeconds)

        val todo = TodoData("id1", "t", "n", 99L, true, 2, "home")
        assertEquals("id1", todo.id); assertEquals("t", todo.title); assertEquals("n", todo.notes)
        assertEquals(99L, todo.dueAt); assertTrue(todo.done); assertEquals(2, todo.priority); assertEquals("home", todo.list)
        val todoDef = TodoData("i", "x"); assertEquals("", todoDef.notes); assertNull(todoDef.dueAt); assertFalse(todoDef.done)
        val ins = InsertTodoData("a", "b", 5L, 3)
        assertEquals("a", ins.title); assertEquals("b", ins.notes); assertEquals(5L, ins.dueAt); assertEquals(3, ins.priority)
        val insDef = InsertTodoData("only"); assertEquals("", insDef.notes); assertNull(insDef.dueAt); assertEquals(0, insDef.priority)
        assertEquals("today", GetTodosQuery().scope); assertEquals("all", GetTodosQuery("all").scope)
        val comp = CompleteTodoData(); assertEquals("", comp.id); assertEquals("", comp.title)
        assertEquals("z", CompleteTodoData(id = "z").id)

        val alarm = SetAlarmData(7, 30); assertEquals(7, alarm.hour); assertEquals(30, alarm.minute); assertEquals("", alarm.label)
        val timer = SetTimerData(60); assertEquals(60, timer.seconds); assertEquals("", timer.label)
        val ev = EventData("meet", 1L, 2L); assertEquals("meet", ev.title); assertEquals(1L, ev.start); assertEquals(2L, ev.end); assertEquals("", ev.location)
        val contact = ContactData("Ann", "123"); assertEquals("Ann", contact.name); assertEquals("123", contact.phoneNumber)
        val email = EmailData("s", "f", "t", "d", "b", true)
        assertEquals("s", email.subject); assertEquals("f", email.from); assertEquals("t", email.to)
        assertEquals("d", email.date); assertEquals("b", email.body); assertTrue(email.isRead)
        assertEquals("q", EmailSearchQuery("q").query)
        val fam = FamilyMemberData("Bo", "Park", 1.5, 2.5)
        assertEquals("Bo", fam.name); assertEquals("Park", fam.locationName); assertEquals(1.5, fam.lat); assertEquals(2.5, fam.lon)
        val olr = OrderLookupRequest("Cafe", 1.0, 2.0)
        assertEquals("Cafe", olr.name); assertEquals(1.0, olr.lat); assertEquals(2.0, olr.lng); assertNull(olr.address)
        val olRes = OrderLookupResult(true, 7, "link")
        assertTrue(olRes.available); assertEquals(7, olRes.merchantId); assertEquals("link", olRes.deepLink)
        val ride = RideEstimateRequest(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.0, ride.pickupLat); assertEquals(2.0, ride.pickupLng); assertEquals(3.0, ride.destLat); assertEquals(4.0, ride.destLng)
        assertNull(ride.pickupLabel); assertNull(ride.destLabel)
        val rideRes = RideEstimateResult(false, "", -1)
        assertFalse(rideRes.available); assertEquals("", rideRes.fareEstimate); assertEquals(-1, rideRes.etaMinutes)
        val msr = MusicSearchResult(4L, "song", "track"); assertEquals(4L, msr.id); assertEquals("song", msr.name); assertEquals("track", msr.type)
        val play = PlayMusicData(9L, "album"); assertEquals(9L, play.id); assertEquals("album", play.type)
        val note = NoteData("t", "c"); assertEquals("t", note.title); assertEquals("c", note.content)
        val insNote = InsertNoteData("t2", "c2"); assertEquals("t2", insNote.title); assertEquals("c2", insNote.content)
    }

    // ---- AppMessages.Message defaults + show() dedup/action paths ----
    @Test fun appMessageDefaults() {
        val m = AppMessages.Message("hello")
        assertEquals("hello", m.text); assertNull(m.actionLabel); assertNull(m.onAction)
        assertEquals(AppMessages.Duration.Short, m.duration)
        val m2 = AppMessages.Message("x", "Act", { }, AppMessages.Duration.Indefinite)
        assertEquals("Act", m2.actionLabel); assertEquals(AppMessages.Duration.Indefinite, m2.duration)
        AppMessages.show("wave2-unique-${System.nanoTime()}")
        AppMessages.show("wave2-action-${System.nanoTime()}", actionLabel = "Go", duration = AppMessages.Duration.Long) {}
    }

    // ---- Markdown: hidden markers on task lists, unknown latex, hidden headers ----
    @Test fun markdownHiddenTaskAndUnknownLatex() {
        val task = parseMarkdown("- [x] done thing\n- [ ] open thing", showMarkers = false)
        assertTrue(task.text.contains("done thing"))
        val hiddenHeader = parseMarkdown("# Title\n\n- item", showMarkers = false)
        assertTrue(hiddenHeader.text.contains("Title"))
        val unknown = parseMarkdown("""$\unknowncmd{abc}$ and $\mathbf{bold}$""", showMarkers = false)
        assertTrue(unknown.text.contains("abc"))
        val allCmds = parseMarkdown(
            """$\cancel{a}$ $\mathrm{b}$ $\underline{c}$ $\mathtt{d}$ $\mathsf{e}$ $\mathit{f}$""",
            showMarkers = true,
        )
        assertTrue(allCmds.spanStyles.isNotEmpty())
        val hiddenList = parseMarkdown("  - nested\n    - deeper", showMarkers = false, process = true)
        assertTrue(hiddenList.text.contains("deeper"))
    }

    // ---- BackupHelper: restore-warn, directory zip/unzip, default-named datastore ----
    @Test fun backupRestoreWarnAndDirectories() {
        // dbConfigs with no codec on RESTORE hits warnRestoreSkipped.
        val emptyZip = ByteArrayOutputStream().also {
            BackupHelper.performFullBackup(ctx, emptyList(), emptyList(), emptyList(), emptyList(), it)
        }
        BackupHelper.performFullRestore(ctx, listOf("dbx" to "pw"), emptyList(), emptyList(), emptyMap(),
            ByteArrayInputStream(emptyZip.toByteArray()), null)

        // Directory in zipFiles + directory entry in unzipFiles.
        val dir = File(ctx.cacheDir, "wave2dir").apply { mkdirs() }
        File(dir, "a.txt").writeText("A"); File(dir, "sub").mkdirs(); File(dir, "sub/b.txt").writeText("B")
        val out = ByteArrayOutputStream(); BackupHelper.zipFiles(listOf(dir), ctx.cacheDir, out)
        val target = Files.createTempDirectory("wave2unzip").toFile()
        BackupHelper.unzipFiles(ByteArrayInputStream(out.toByteArray()), target)
        assertEquals("A", File(target, "wave2dir/a.txt").readText())
        assertEquals("B", File(target, "wave2dir/sub/b.txt").readText())

        // Default datastore name restores to filesDir root, not datastore/ subdir.
        val src = File(ctx.filesDir, "datastore_default.preferences_pb").apply { writeBytes(byteArrayOf(7)) }
        try {
            val o2 = ByteArrayOutputStream()
            BackupHelper.performFullBackup(ctx, emptyList(), listOf("datastore_default"), emptyList(), emptyList(), o2)
            src.delete()
            BackupHelper.performFullRestore(ctx, emptyList(), listOf("datastore_default"), emptyList(), emptyMap(),
                ByteArrayInputStream(o2.toByteArray()))
            assertTrue(File(ctx.filesDir, "datastore_default.preferences_pb").exists())
        } finally { src.delete() }

        // performFullBackup/Restore with omitted optional args ($default synthetics).
        val o3 = ByteArrayOutputStream()
        BackupHelper.performFullBackup(ctx, emptyList(), extraFiles = emptyList(), outputStream = o3)
        BackupHelper.performFullRestore(ctx, emptyList(), extraFilesMapping = emptyMap(),
            inputStream = ByteArrayInputStream(o3.toByteArray()))
    }

    // ---- BaseBackupAgent default properties ----
    class DefaultsAgent : BaseBackupAgent() { fun attach(c: Context) = attachBaseContext(c) }
    @Test fun backupAgentDefaults() {
        val agent = DefaultsAgent().apply { attach(ctx) }
        assertTrue(agent.dbConfigs.isEmpty()); assertTrue(agent.datastoreNames.isEmpty())
        assertTrue(agent.prefNames.isEmpty()); assertTrue(agent.extraFiles.isEmpty()); assertNull(agent.dbCodec)
        // onBackup with null data/newState must still run without throwing.
        agent.onBackup(null, null, null)
        agent.onRestore(null, 1, null)
    }

    // ---- IntentHelper: content provider display-name paths ----
    class NameProvider(private val cursor: Cursor?) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = cursor
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, v: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    }
    @Test fun intentHelperContentProviderNames() {
        val named = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("photo.jpg")) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("com.wave2.named", NameProvider(named))
        assertEquals("photo.jpg", IntentHelper.getFileName(ctx, Uri.parse("content://com.wave2.named/items/1")))

        val noColumn = MatrixCursor(arrayOf("other")).apply { addRow(arrayOf("x")) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("com.wave2.nocol", NameProvider(noColumn))
        assertEquals("42", IntentHelper.getFileName(ctx, Uri.parse("content://com.wave2.nocol/items/42")))

        val empty = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME))
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("com.wave2.empty", NameProvider(empty))
        assertEquals("7", IntentHelper.getFileName(ctx, Uri.parse("content://com.wave2.empty/items/7")))
    }

    // ---- IntentLauncher: unresolvable package returns immediately ----
    @Test fun intentLauncherMissingPackageTimesOut(): Unit = runBlocking {
        val activity = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().get()
        val launcher = IntentLauncher(activity)
        // Robolectric resolves the component but no target ever answers, so the
        // bounded launch must surface TimeoutException instead of hanging.
        kotlin.test.assertFailsWith<java.util.concurrent.TimeoutException> {
            launcher.launch(ctx, "com.wave2.absent", "com.wave2.absent.Nope",
                NoteData.serializer(), NoteData("t", "c"))
        }
    }

    // ---- Default-arg constructors ----
    class TwoArgManager(context: Context) : AchievementsManager(context, "[]") {
        override fun checkExistingAchievements() {}
    }
    @Test fun defaultArgConstructors() = runBlocking {
        val manager = TwoArgManager(ctx)
        assertEquals(ctx.applicationContext, manager.context)
        assertTrue(manager.achievements.isEmpty())
        manager.forgetAll()
        val repo = LevelStatsRepository(ctx)
        assertEquals(emptyMap(), repo.getLevelStats())
        repo.updateBestScore("lvl-wave2", 10); repo.updateBestScore("lvl-wave2", 20)
        repo.updateBestScore("lvl-wave2", 5)
        assertEquals(5, repo.getLevelStats()["lvl-wave2"]!!.bestScore)
        repo.retainOnly(setOf("lvl-wave2")); repo.retainOnly(emptySet())
        assertTrue(repo.getLevelStats().isEmpty())
        val helper = DatabaseHelper(ctx)
        assertEquals(ctx, helper.context)
        helper.deleteKey()
    }

    // ---- Iteration 2 ----
    @Test fun localizedAllAvailableLocalesSweep() {
        var sawFridayOrSaturday = false
        for (loc in Locale.getAvailableLocales()) {
            val first = localeFirstDayOfWeek(loc)
            assertTrue(first in 1..7, "$loc")
            assertEquals(first, localeWeekDayNumbers(loc).first(), "$loc")
            if (first >= 5) sawFridayOrSaturday = true
        }
        assertTrue(sawFridayOrSaturday)
        assertTrue(10f.formatSpeed(Locale.US).isNotBlank())
        assertTrue(10f.formatSpeed(Locale.FRANCE).isNotBlank())
        assertTrue(0f.formatSpeed().isNotBlank())
        with(ctx) {
            ensureNotificationChannel("wave2-ch", "Wave2")
            ensureNotificationChannel("wave2-ch2", "Wave2b", description = "desc") { enableLights(true) }
            deleteNotificationChannel("wave2-ch")
        }
        // Explicit directory entry in a zip must be recreated by unzipFiles.
        val zout = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zout).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("emptydir/")); zos.closeEntry()
            zos.putNextEntry(java.util.zip.ZipEntry("emptydir/f.txt")); zos.write("hi".toByteArray()); zos.closeEntry()
        }
        val zt = Files.createTempDirectory("wave2zipdir").toFile()
        BackupHelper.unzipFiles(ByteArrayInputStream(zout.toByteArray()), zt)
        assertTrue(File(zt, "emptydir").isDirectory)
        assertEquals("hi", File(zt, "emptydir/f.txt").readText())
    }

    class ThrowingActivity : androidx.activity.ComponentActivity() {
        override fun startActivity(intent: android.content.Intent) {
            if (mode == 1) throw SecurityException("denied-wave2")
            throw android.content.ActivityNotFoundException("missing-wave2")
        }
        companion object { var mode = 1 }
    }

    @Test fun intentLauncherStartFailuresSurface(): Unit = runBlocking {
        for (m in listOf(1, 2)) {
            ThrowingActivity.mode = m
            val activity = Robolectric.buildActivity(ThrowingActivity::class.java).create().get()
            val launcher = IntentLauncher(activity)
            val deferred = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
                runCatching {
                    launcher.launch(ctx, "com.wave2.absent", "com.wave2.absent.Nope",
                        NoteData.serializer(), NoteData("t", "c"))
                }
            }
            val deadline = System.currentTimeMillis() + 15_000
            while (!deferred.isCompleted && System.currentTimeMillis() < deadline) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertTrue(deferred.isCompleted, "launcher must complete for mode $m")
            assertTrue(deferred.await().isFailure, "mode $m must fail: ${deferred.await()}")
        }
    }
}
