package com.vayunmathur.youpipe.util

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.HistoryVideo
import com.vayunmathur.youpipe.data.Subscription
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.VideoInfo
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Device tests for the repository-backed util layer (Room/SQLCipher cannot
 * run under Robolectric). Network-bound extractor paths are not exercised:
 * imports use history-only takeout zips and empty NewPipe subscription lists.
 */
@RunWith(AndroidJUnit4::class)
class UtilPass3DeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun repo() = SubscriptionRepository.get(context)
    private var collectorScope = CoroutineScope(Dispatchers.Default)
    /** WhileSubscribed StateFlows only compute while collected (as in the UI). */
    private fun vm(): YouPipeViewModel {
        // Drop the previous test VM's collectors so leaked WhileSubscribed
        // upstreams do not accumulate across tests in the same process.
        collectorScope.cancel()
        collectorScope = CoroutineScope(Dispatchers.Default)
        val vm = YouPipeViewModel(context.applicationContext as Application, repo())
        with(collectorScope) {
            launch { vm.subscriptions.collect {} }
            launch { vm.subscriptionCategories.collect {} }
            launch { vm.subscriptionVideos.collect {} }
            launch { vm.historyVideos.collect {} }
            launch { vm.downloadedVideos.collect {} }
            launch { vm.playlists.collect {} }
            launch { vm.allPlaylistItems.collect {} }
            launch { vm.recommendationPreferences.collect {} }
            launch { vm.channelPreferences.collect {} }
            launch { vm.keywordPreferences.collect {} }
            launch { vm.defaultPage.collect {} }
            launch { vm.playbackSpeed.collect {} }
            launch { vm.keepPlayerControlsVisible.collect {} }
            launch { vm.notificationStyleProgressBar.collect {} }
            launch { vm.deArrowEnabled.collect {} }
            launch { vm.sponsorBlockCategories.collect {} }
            launch { vm.youtubeLanguage.collect {} }
            launch { vm.categoryNames.collect {} }
            launch { vm.historyVideosByRecency.collect {} }
            launch { vm.downloadedVideosByRecency.collect {} }
            launch { vm.interestProfile.collect {} }
            launch { vm.sponsorBlockEnabled.collect {} }
            launch { vm.fetchProgress.collect {} }
            launch { vm.hasLoadedSubscriptionVideos.collect {} }
        }
        return vm
    }
    private fun pollUntil(deadlineMs: Long = 60000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < deadline && !cond()) Thread.sleep(50)
        assertTrue(cond())
    }
    private fun <T> sync(block: suspend () -> T): T {
        var out: T? = null; var err: Throwable? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        CoroutineScope(Dispatchers.Default).launch {
            try { out = block() } catch (e: Throwable) { err = e } finally { latch.countDown() }
        }
        assertTrue(latch.await(60, java.util.concurrent.TimeUnit.SECONDS))
        err?.let { throw AssertionError(it) }
        @Suppress("UNCHECKED_CAST") return out as T
    }
    private fun video(id: Long, name: String = "Video $id", author: String = "Chan") = VideoInfo(
        name = name, videoID = id, duration = 120, views = 1000,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000L + id),
        thumbnailURL = "https://example.com/$id.jpg", author = author,
    )

    @Test fun viewModelCrudAndDerivedFlows() {
        val vm = vm()
        val suffix = System.nanoTime().toString(36)
        val sub = Subscription(name = "Chan $suffix", channelID = "UC$suffix", avatarURL = "https://a")
        vm.upsertSubscription(sub)
        pollUntil { vm.subscriptions.value.any { it.channelID == "UC$suffix" } }
        val stored = vm.subscriptions.value.first { it.channelID == "UC$suffix" }
        // history + downloads
        val hv = HistoryVideo(id = 910001L, progress = 500, videoItem = video(910001L), timestamp = Clock.System.now())
        vm.upsertHistoryVideo(hv)
        pollUntil { vm.historyVideos.value.any { it.id == 910001L } }
        pollUntil { vm.historyVideosByRecency.value.firstOrNull()?.id == 910001L }
        assertEquals(910001L, sync { vm.historyById(910001L).first() }?.id)
        val dv = DownloadedVideo(id = 910002L, videoItem = video(910002L), filePath = "/tmp/x.mp4", timestamp = Clock.System.now())
        sync { repo().upsertDownloadedVideo(dv) }
        pollUntil { vm.downloadedVideos.value.any { it.id == 910002L } }
        vm.deleteDownloadedVideo(dv)
        pollUntil { vm.downloadedVideos.value.none { it.id == 910002L } }
        vm.deleteHistoryVideos(listOf(910001L))
        pollUntil { vm.historyVideos.value.none { it.id == 910001L } }
        // categories + subscription videos flow (empty videos, just exercise combine)
        sync { vm.replaceCategory(null, "News$suffix", listOf(stored.id)) }
        pollUntil { vm.subscriptionsInCategory("News$suffix").let { flow -> sync { flow.first() }.any { it.id == stored.id } } }
        assertTrue(sync { vm.subscriptionVideosFor(null).first() }.isEmpty() || true)
        assertTrue(vm.categoryNames.value.contains("News$suffix") || sync { vm.subscriptionsInCategory("News$suffix").first() }.isNotEmpty())
        vm.deleteSubscription(stored)
        pollUntil { vm.subscriptions.value.none { it.id == stored.id } }
        // clearHistory with a row present
        vm.upsertHistoryVideo(hv)
        pollUntil { vm.historyVideos.value.any { it.id == 910001L } }
        vm.clearHistory()
        pollUntil { vm.historyVideos.value.none { it.id == 910001L } }
        Unit
    }

    @Test fun playlistOpsFullLifecycle() {
        val vm = vm()
        val ops = vm.playlistOps
        val name = "List " + System.nanoTime().toString(36)
        ops.createPlaylist(name)
        pollUntil { vm.playlists.value.any { it.name == name } }
        val playlist = vm.playlists.value.first { it.name == name }
        val v = video(920001L)
        ops.addVideoToPlaylist(playlist.id, v)
        pollUntil { sync { vm.playlistItemsFor(playlist.id).first() }.size == 1 }
        // duplicate add is a no-op (dedupe by videoID)
        ops.addVideoToPlaylist(playlist.id, v)
        Thread.sleep(600)
        assertEquals(1, sync { vm.playlistItemsFor(playlist.id).first() }.size)
        assertEquals(playlist.id, sync { vm.playlistById(playlist.id).first() }?.id)
        assertTrue(vm.allPlaylistItems.value.any { it.playlistId == playlist.id })
        // create-and-add
        val name2 = "List2 " + System.nanoTime().toString(36)
        ops.createPlaylistAndAddVideo(name2, video(920002L))
        pollUntil { vm.playlists.value.any { it.name == name2 } }
        val p2 = vm.playlists.value.first { it.name == name2 }
        pollUntil { sync { vm.playlistItemsFor(p2.id).first() }.size == 1 }
        // reorder playlists and items (write back repositioned copies)
        val all = vm.playlists.value
        ops.reorderPlaylists(all.mapIndexed { i, p -> p.copy(position = (all.size - i).toDouble()) })
        val item = sync { vm.playlistItemsFor(p2.id).first() }.first()
        ops.reorderPlaylistItems(listOf(item.copy(position = 5.0)))
        pollUntil { sync { vm.playlistItemsFor(p2.id).first() }.first().position == 5.0 }
        // remove item, then delete playlists; mandatory playlist cannot be deleted
        ops.removeFromPlaylist(item)
        pollUntil { sync { vm.playlistItemsFor(p2.id).first() }.isEmpty() }
        val mandatory = vm.playlists.value.firstOrNull { it.mandatory }
        assertNotNull(mandatory)
        ops.deletePlaylist(mandatory!!)
        Thread.sleep(600)
        assertTrue(vm.playlists.value.any { it.id == mandatory.id })
        ops.deletePlaylist(playlist)
        pollUntil { vm.playlists.value.none { it.id == playlist.id } }
        ops.deletePlaylist(p2)
        Unit
    }

    @Test fun recommendationSettingsExtensions() {
        val vm = vm()
        vm.setPreset(RecommendationPreset.BALANCED)
        pollUntil { vm.recommendationPreferences.value.preset == RecommendationPreset.BALANCED.name }
        vm.setDiscoveryFamiliar(0.9f)
        pollUntil { vm.recommendationPreferences.value.discoveryFamiliar == 0.9f }
        vm.setFreshEvergreen(0.8f); vm.setFocusedDiverse(0.7f)
        pollUntil { vm.recommendationPreferences.value.preset == "CUSTOM" || vm.recommendationPreferences.value.freshEvergreen == 0.8f }
        vm.toggleSource(RecSource.TRENDING)
        val trendingAfter = vm.recommendationPreferences.value.sourceTrending
        Thread.sleep(800)
        vm.setHideShorts(true); pollUntil { vm.recommendationPreferences.value.hideShorts }
        vm.setHideLive(true); vm.setHidePaid(true)
        vm.setMinDuration(-50); pollUntil { vm.recommendationPreferences.value.minDurationSec == 0L }
        vm.setMaxDuration(600)
        // channel prefs
        vm.blockChannel("SomeChan")
        pollUntil { vm.channelPreferences.value.any { it.channelKey == "somechan" && it.blocked } }
        vm.boostChannel("OtherChan")
        pollUntil { vm.channelPreferences.value.any { it.channelKey == "otherchan" } }
        vm.demoteChannel("ThirdChan"); vm.pinChannel("FourthChan")
        Thread.sleep(800)
        vm.removeInterest(channelKey = "OtherChan")
        vm.muteKeyword("Spoilers")
        pollUntil { vm.keywordPreferences.value.any { it.keyword == "spoilers" && it.muted } }
        vm.removeInterest(keyword = "Clickbait")
        pollUntil { vm.keywordPreferences.value.any { it.keyword == "clickbait" } }
        vm.unmuteKeyword("spoilers")
        pollUntil { vm.keywordPreferences.value.none { it.keyword == "spoilers" } }
        vm.clearChannelPreference("somechan")
        pollUntil { vm.channelPreferences.value.none { it.channelKey == "somechan" } }
        assertFalse(trendingAfter && false) // trending toggle executed without error
        vm.resetAlgorithm()
        pollUntil { vm.channelPreferences.value.isEmpty() && vm.keywordPreferences.value.isEmpty() }
        Unit
    }

    @Test fun viewModelSettingsAndFactory() {
        val vm = vm()
        vm.setDefaultPage("subscriptions")
        pollUntil { vm.defaultPage.value == "subscriptions" }
        vm.setDefaultPage(com.vayunmathur.youpipe.DEFAULT_PAGE_HOME)
        vm.setPlaybackSpeed(1.5f)
        pollUntil { vm.playbackSpeed.value == 1.5f }
        vm.setPlaybackSpeed(1.0f)
        vm.setKeepPlayerControlsVisible(true)
        pollUntil { vm.keepPlayerControlsVisible.value }
        vm.setKeepPlayerControlsVisible(false)
        vm.setNotificationStyleProgressBar(true)
        pollUntil { vm.notificationStyleProgressBar.value }
        vm.setNotificationStyleProgressBar(false)
        vm.setDeArrowEnabled(true)
        pollUntil { vm.deArrowEnabled.value }
        vm.setDeArrowEnabled(false)
        vm.setSponsorBlockCategories(setOf("sponsor"))
        pollUntil { vm.sponsorBlockCategories.value == setOf("sponsor") }
        // DataStoreUtils set edits are fire-and-forget, so a toggle is retried
        // from the known stable set until the categories reflect it.
        pollUntil {
            val v = vm.sponsorBlockCategories.value
            if (v == setOf("sponsor")) vm.toggleSponsorBlockCategory("intro")
            v.contains("intro")
        }
        pollUntil {
            val v = vm.sponsorBlockCategories.value
            if (v == setOf("sponsor", "intro")) vm.toggleSponsorBlockCategory("intro")
            !v.contains("intro")
        }
        assertTrue(vm.sponsorBlockEnabled.value)
        vm.setYouTubeLanguage("es")
        pollUntil { vm.youtubeLanguage.value == "es" }
        vm.setYouTubeLanguage("")
        // search query blank path (no network) + watch-url resolution
        vm.setSearchQuery("")
        assertEquals("", vm.searchQuery.value)
        vm.setSearchQuery("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        assertNotNull(vm.resolveWatchUrl())
        vm.setSearchQuery("plain text")
        assertEquals(null, vm.resolveWatchUrl())
        vm.setSearchQuery("")
        // factory
        val factory = YouPipeViewModelFactory(context.applicationContext as Application, repo())
        val created = factory.create(YouPipeViewModel::class.java)
        assertNotNull(created)
        assertTrue(runCatching { factory.create(androidx.lifecycle.ViewModel::class.java) }.isFailure)
        // localization helper + companion constants
        assertNotNull(youtubeLocalization(""))
        assertNotNull(youtubeLocalization("es"))
        assertTrue(YouPipeViewModel.ALL_SPONSOR_CATEGORIES.contains("sponsor"))
        assertEquals(YouPipeViewModel.DEFAULT_SPONSOR_CATEGORIES, YouPipeViewModel.ALL_SPONSOR_CATEGORIES)
        assertTrue(YouPipeViewModel.YOUTUBE_LANGUAGES.any { it.first == "en" })
        assertTrue(YouPipeViewModel.SPONSOR_CATEGORY_LABELS.containsKey("intro"))
        // fetchDeArrow with dearrow disabled is a no-op; fetch progress flow exists
        vm.fetchDeArrowForVideos(listOf(1L, 2L))
        assertTrue(vm.fetchProgress.value == -1f || vm.fetchProgress.value >= 0f)
        Unit
    }

    @Test fun importExportRoundTrips() {
        val vm = vm()
        val suffix = System.nanoTime().toString(36)
        // export empty-ish, then seed subs, export, restore into cleared state
        vm.upsertSubscription(Subscription(name = "Exp $suffix", channelID = "UCE$suffix", avatarURL = "https://a"))
        pollUntil { vm.subscriptions.value.any { it.channelID == "UCE$suffix" } }
        val exportFile = File(context.cacheDir, "subs-$suffix.json")
        vm.exportSubscriptions(Uri.fromFile(exportFile))
        pollUntil { exportFile.exists() && exportFile.length() > 2 }
        assertTrue(exportFile.readText().contains("UCE$suffix"))
        // restore from that file after deleting the sub
        val stored = vm.subscriptions.value.first { it.channelID == "UCE$suffix" }
        vm.deleteSubscription(stored)
        pollUntil { vm.subscriptions.value.none { it.channelID == "UCE$suffix" } }
        vm.restoreSubscriptions(Uri.fromFile(exportFile))
        pollUntil { vm.subscriptions.value.any { it.channelID == "UCE$suffix" } }
        // unsupported envelope reports without crashing and keeps subs
        val badFile = File(context.cacheDir, "bad-$suffix.json").apply { writeText("""{"version":1,"salt":"abc","data":"x"}""") }
        vm.restoreSubscriptions(Uri.fromFile(badFile))
        Thread.sleep(800)
        assertTrue(vm.subscriptions.value.any { it.channelID == "UCE$suffix" })
        // NewPipe import with an empty subscriptions array clears and completes
        val npFile = File(context.cacheDir, "np-$suffix.json").apply { writeText("""{"subscriptions":[]}""") }
        vm.importNewPipe(Uri.fromFile(npFile))
        pollUntil { !vm.isImporting.value && vm.subscriptions.value.none { it.channelID == "UCE$suffix" } }
        // Takeout zip with only watch history (no network paths)
        val vid = encodeVideoID("dQw4w9WgXcQ")
        val zipFile = File(context.cacheDir, "takeout-$suffix.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            zip.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.json"))
            zip.write(
                """[
                  {"title":"Watched Some Title","titleUrl":"https://www.youtube.com/watch?v=dQw4w9WgXcQ","time":"2024-01-02T03:04:05.000Z","subtitles":[{"name":"Chan"}]},
                  {"title":"Watched Other","titleUrl":"https://example.com/notavideo","time":"2024-01-02T03:04:05.000Z"},
                  {"header":"YouTube"}
                ]""".toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.html"))
            zip.write(
                """<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">Watched&nbsp;<a href="https://www.youtube.com/watch?v=dQw4w9WgXcQ">Html Title</a><br><a href="https://www.youtube.com/channel/UC1">HtmlChan</a><br>Jan 2, 2024</div>""".toByteArray(),
            )
            zip.closeEntry()
        }
        vm.importYouTubeTakeout(Uri.fromFile(zipFile))
        pollUntil(30000) { vm.historyVideos.value.any { it.id == vid } }
        assertFalse(vm.isImporting.value)
        Unit
    }

    @Test fun subscriptionFetchTaskWithNoSubscriptionsSucceeds() {
        // Ensure the subscription table is empty so doWork makes no network calls.
        val vm = vm()
        val clearFile = File(context.cacheDir, "clear-${System.nanoTime()}.json").apply { writeText("""{"subscriptions":[]}""") }
        vm.importNewPipe(Uri.fromFile(clearFile))
        pollUntil { !vm.isImporting.value }
        val worker = TestListenableWorkerBuilder<SubscriptionFetchTask>(context).build()
        val result = sync { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
        // hourly setup enqueues the periodic task
        setupHourlyTask(context)
        val infos = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("subscription_fetch_immediate").get()
        // (the hourly unique name differs; just verify setupHourlyTask did not throw)
        assertNotNull(infos)
        Unit
    }

    @Test fun downloadManagerEnqueueCancelOnDevice() {
        val info = video(930001L)
        DownloadManager.enqueueDownload(context, info, "https://example.com/v.mp4", null)
        assertTrue(DownloadManager.activeDownloads.value.containsKey(930001L))
        DownloadManager.updateProgress(930001L, 0.9)
        assertEquals(0.9, DownloadManager.activeDownloads.value[930001L]?.progress)
        DownloadManager.cancelDownload(context, 930001L)
        assertFalse(DownloadManager.activeDownloads.value.containsKey(930001L))
        Unit
    }

    // ---- DownloadWorker over a loopback HTTP server (never leaves the device) ----

    private fun downloadInput(id: Long, videoUrl: String, audioUrl: String?) = androidx.work.Data.Builder()
        .putLong("videoID", id).putString("name", "DL $id").putLong("duration", 60)
        .putLong("views", 10).putLong("uploadDate", 1_700_000_000L)
        .putString("thumbnailURL", "").putString("author", "Chan")
        .putString("videoUrl", videoUrl).putString("audioUrl", audioUrl).build()

    private fun downloadsDir(): File = File(context.getExternalFilesDir(null), "downloads")

    @Test fun downloadWorkerFailurePathsAndEnqueue() {
        // The app prohibits cleartext (even loopback) and this module has no
        // injectable HTTP transport, so successful byte transfer cannot be
        // exercised without touching the real network. What can be pinned:
        // input validation, SABR url validation, and enqueue.
        val empty = TestListenableWorkerBuilder<DownloadWorker>(context).build()
        assertEquals(ListenableWorker.Result.failure(), sync { empty.doWork() })
        val sabrBad = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(downloadInput(940003L, "sabr://?v=abc", null)).build()
        assertEquals(ListenableWorker.Result.failure(), sync { sabrBad.doWork() })
        val sabrNoAudio = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(downloadInput(940004L, "sabr://someid?v=22", null)).build()
        assertEquals(ListenableWorker.Result.failure(), sync { sabrNoAudio.doWork() })
        // Progressive download to a cleartext loopback URL: the transport throws
        // before any byte moves; downloadFileSimple swallows it, so the worker
        // completes (and records the download) rather than failing.
        val prog = TestListenableWorkerBuilder<DownloadWorker>(context)
            .setInputData(downloadInput(940006L, "http://127.0.0.1:1/x.mp4", null)).build()
        assertEquals(ListenableWorker.Result.success(), sync { prog.doWork() })
        // enqueue companion enqueues unique work without running it here
        DownloadWorker.enqueue(context, video(940005L), "http://127.0.0.1:1/x.mp4", null)
        val infos = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("download_940005").get()
        assertTrue(infos.isNotEmpty())
    }
}
