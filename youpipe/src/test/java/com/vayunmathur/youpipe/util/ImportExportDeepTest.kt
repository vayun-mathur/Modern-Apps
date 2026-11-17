package com.vayunmathur.youpipe.platform

import android.app.Application
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows

/** Import/export through real files: Takeout zip, NewPipe JSON, backup restore/export. */
@RunWith(AndroidJUnit4::class)
class ImportExportDeepTest {
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private lateinit var repo: SubscriptionRepository
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val dir: File get() = app.cacheDir

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        repo = SubscriptionRepository(app, db); vm = YouPipeViewModel(app, repo)
        runBlocking { repo.subscriptionStore.upsertSubscription(Subscription(name = "Keep Me", channelID = "UCkeep", avatarURL = "")) }
    }
    @AfterTest fun tearDown() { Thread.sleep(100) }

    private fun awaitIdle(cond: () -> Boolean = { !vm.isImporting.value }) {
        val d = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < d) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50); if (cond()) return }
        assertTrue(cond(), "import never finished")
    }
    private fun fileUri(name: String, content: String): Uri {
        val f = File(dir, name); f.writeText(content); return Uri.fromFile(f)
    }

    @Test fun exportThenRestore_roundTripsSubscriptions() {
        val out = File(dir, "backup.json")
        vm.exportSubscriptions(Uri.fromFile(out))
        val d = System.currentTimeMillis() + 10_000
        while (!out.exists() || out.length() == 0L) { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(50); if (System.currentTimeMillis() > d) break }
        assertTrue(out.readText().contains("UCkeep"), out.readText())
        runBlocking { repo.subscriptionStore.clearAllSubscriptions() }
        vm.restoreSubscriptions(Uri.fromFile(out)); awaitIdle()
        val subs = runBlocking { repo.subscriptionStore.getAllSubscriptions() }
        assertEquals(listOf("UCkeep"), subs.map { it.channelID })
    }

    @Test fun restore_unsupportedEnvelope_keepsExistingAndReportsDone() {
        vm.restoreSubscriptions(fileUri("env.json", """{"version":1,"salt":"abc","data":"x"}""")); awaitIdle()
        assertEquals(listOf("UCkeep"), runBlocking { repo.subscriptionStore.getAllSubscriptions() }.map { it.channelID })
    }

    @Test fun restore_malformedJson_keepsExisting() {
        vm.restoreSubscriptions(fileUri("bad.json", "not json at all")); awaitIdle()
        assertEquals(listOf("UCkeep"), runBlocking { repo.subscriptionStore.getAllSubscriptions() }.map { it.channelID })
    }

    @Test fun importNewPipe_emptyAndMissingSubscriptions_noCrash() {
        vm.importNewPipe(fileUri("np1.json", """{"subscriptions":[]}""")); awaitIdle()
        // Empty list still clears + re-seeds path (existing replaced by empty import).
        vm.importNewPipe(fileUri("np2.json", """{"other":true}""")); awaitIdle()
        assertTrue(!vm.isImporting.value)
    }

    @Test fun importNewPipe_unreachableChannel_isSkippedNotFatal() {
        vm.importNewPipe(fileUri("np3.json", """{"subscriptions":[{"url":"youtube.com/channel/UCunreachable123"}]}"""))
        awaitIdle()
        assertTrue(!vm.isImporting.value)
    }

    @Test fun importTakeout_jsonHistory_importsWatchedVideos() {
        val zip = File(dir, "takeout.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.json"))
            z.write("""[{"title":"Watched Some Clip","titleUrl":"https://www.youtube.com/watch?v=AAAAAAAAAAA","time":"2024-01-02T03:04:05.000Z","subtitles":[{"name":"Chan One"}]},{"title":"No URL entry"}]""".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv"))
            z.write("Channel Id,Channel Url,Channel Title\nUCx,https://www.youtube.com/channel/UCunreachable123,Nope\n".toByteArray()); z.closeEntry()
        }
        vm.importYouTubeTakeout(Uri.fromFile(zip)); awaitIdle { !vm.isImporting.value && runBlocking { repo.historyStore.getAllHistoryVideos() }.isNotEmpty() }
        val hist = runBlocking { repo.historyStore.getAllHistoryVideos() }
        assertTrue(hist.any { it.videoItem.name.contains("Some Clip") }, hist.toString())
    }

    @Test fun importTakeout_htmlHistory_andEmptyZip_noCrash() {
        val zip = File(dir, "takeout2.zip")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("history/watch-history.html"))
            z.write("""<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">Watched&nbsp;<a href="https://www.youtube.com/watch?v=BBBBBBBBBBB">HTML Clip</a><br><a href="https://www.youtube.com/channel/UCx">HTML Chan</a><br>Jan 3, 2024</div>""".toByteArray()); z.closeEntry()
        }
        vm.importYouTubeTakeout(Uri.fromFile(zip)); awaitIdle()
        val empty = File(dir, "empty.zip"); ZipOutputStream(empty.outputStream()).use {}
        vm.importYouTubeTakeout(Uri.fromFile(empty)); awaitIdle()
        assertTrue(!vm.isImporting.value)
    }
}
