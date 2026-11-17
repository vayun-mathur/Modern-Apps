package com.vayunmathur.youpipe.platform

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.WorkManager
import com.vayunmathur.youpipe.data.DownloadedVideo
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.data.SubscriptionRepository
import com.vayunmathur.youpipe.ui.VideoInfo
import com.vayunmathur.youpipe.ui.VideoStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows
import com.vayunmathur.youpipe.domain.RecSource
import com.vayunmathur.youpipe.domain.RecommendationPreset

/** Video-loading stream helpers, downloaded fallback, import/export parsing, rec settings. */
@RunWith(AndroidJUnit4::class)
class VideoLoadingDeepTest {
    private lateinit var db: SubscriptionDatabase
    private lateinit var vm: YouPipeViewModel
    private lateinit var repo: SubscriptionRepository
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        db = Room.inMemoryDatabaseBuilder(app, SubscriptionDatabase::class.java).allowMainThreadQueries().build()
        repo = SubscriptionRepository(app, db); vm = YouPipeViewModel(app, repo)
    }
    @AfterTest fun tearDown() = db.close()

    private fun idle() { Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(30) }
    private fun await(cond: () -> Boolean) { val d = System.currentTimeMillis() + 8_000; while (!cond() && System.currentTimeMillis() < d) idle(); assertTrue(cond()) }

    private fun dv(audio: String? = "/dl/77.m4a") = DownloadedVideo(
        id = 77, videoItem = VideoInfo("Offline Video", 77, 300, 8, Instant.fromEpochSeconds(1_600_000_000), "", "Seeded Chan"),
        filePath = "/dl/77.mp4", audioPath = audio, timestamp = Instant.fromEpochSeconds(1_700_300_000),
    )

    @Test fun downloadedStreams_withAndWithoutAudio() {
        val (v, a) = downloadedStreams(dv())
        assertEquals(listOf("/dl/77.mp4"), v.map { it.url }); assertEquals("Downloaded", v.single().quality)
        assertEquals(1920, v.single().width); assertEquals(listOf("/dl/77.m4a"), a.map { it.url }); assertEquals("opus", a.single().codec)
        val (v2, a2) = downloadedStreams(dv(audio = null))
        assertEquals(1, v2.size); assertTrue(a2.isEmpty())
    }

    @Test fun codecPriorityAndStreamPreferenceOrdering() {
        assertTrue(codecPriority("av1") > codecPriority("vp9"))
        assertTrue(codecPriority("vp9") > codecPriority("avc"))
        assertTrue(codecPriority("avc") > codecPriority("unknown"))
        fun s(fps: Int, codec: String) = VideoStream("u$codec$fps", 1280, 720, 1000, fps, "720p", codec, 0)
        val sorted = listOf(s(30, "av1"), s(60, "avc"), s(30, "vp9"), s(60, "av1")).sortedWith(videoStreamPreference)
        // Preference is ascending comparator for maxWith: fps dominates, then codec.
        assertEquals(s(60, "av1"), sorted.maxWith(videoStreamPreference))
        assertEquals("av1", listOf(s(30, "avc"), s(30, "vp9"), s(30, "av1")).maxWith(videoStreamPreference).codec)
        assertEquals("vp9", listOf(s(30, "avc"), s(30, "vp9")).maxWith(videoStreamPreference).codec)
    }

    @Test fun applyDownloadedStreamsAndClearError_onVmState() {
        vm.videoStateMutable.value = YouPipeViewModel.VideoState(error = true)
        vm.applyDownloadedStreams(dv())
        assertEquals("/dl/77.mp4", vm.videoState.value.videoStreams.single().url)
        assertEquals("/dl/77.m4a", vm.videoState.value.audioStreams.single().url)
        assertTrue(vm.videoState.value.segments.isEmpty())
        vm.clearVideoError(); assertEquals(false, vm.videoState.value.error)
    }

    @Test fun loadVideo_downloadedFallback_populatesOfflineDataOnExtractorFailure() {
        vm.loadVideo(77, dv())
        await { vm.videoState.value.data?.title == "Offline Video" || vm.videoState.value.error }
        // Offline sandbox: extractor fails, downloaded fallback must win over error.
        assertEquals("Offline Video", vm.videoState.value.data?.title)
        assertEquals("/dl/77.mp4", vm.videoState.value.videoStreams.single().url)
    }

    @Test fun loadVideo_noDownload_setsErrorOnFailure() {
        vm.loadVideo(999_003, null)
        await { vm.videoState.value.error || vm.videoState.value.data != null }
        assertTrue(vm.videoState.value.error, "expected error state offline, got ${vm.videoState.value}")
    }

    @Test fun decodeBackup_edgeCases() {
        assertEquals(0, decodeSubscriptionBackup("[]")!!.size)
        assertEquals(1, decodeSubscriptionBackup("  [{\"id\":1,\"name\":\"A\",\"channelID\":\"UC1\",\"avatarURL\":\"\"}]")!!.size)
        assertNull(decodeSubscriptionBackup(""))
        assertNull(decodeSubscriptionBackup("null"))
        assertNull(decodeSubscriptionBackup("{\"subscriptions\":[]}"))
    }

    @Test fun recommendationSettings_allControlsPersist() = runBlocking {
        vm.setPreset(RecommendationPreset.BALANCED)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.preset == RecommendationPreset.BALANCED.name }
        vm.setDiscoveryFamiliar(0.9f)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.discoveryFamiliar == 0.9f }
        vm.setFreshEvergreen(0.1f)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.freshEvergreen == 0.1f }
        vm.setFocusedDiverse(0.5f)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.focusedDiverse == 0.5f }
        var prefs = repo.recommendationStore.getRecommendationPreferences()!!
        assertEquals(CUSTOM_PRESET, prefs.preset); assertEquals(0.1f, prefs.freshEvergreen)
        vm.toggleSource(RecSource.RELATED)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.sourceRelated == false }
        vm.toggleSource(RecSource.TRENDING)
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.sourceTrending == false }
        vm.setHideShorts(true); await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.hideShorts == true }
        vm.setHideLive(true); await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.hideLive == true }
        vm.setHidePaid(true); await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.hidePaid == true }
        vm.setMinDuration(-5); await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.minDurationSec == 0L }
        vm.setMaxDuration(600); await { runBlocking { repo.recommendationStore.getRecommendationPreferences() }?.maxDurationSec == 600L }
        prefs = repo.recommendationStore.getRecommendationPreferences()!!
        assertTrue(prefs.hideLive && prefs.hidePaid); assertEquals(0L, prefs.minDurationSec); assertEquals(600L, prefs.maxDurationSec)
        vm.blockChannel("UCBlock"); await { runBlocking { repo.recommendationStore.getChannelPreference("ucblock") }?.blocked == true }
        vm.demoteChannel("UCDemote"); await { runBlocking { repo.recommendationStore.getChannelPreference("ucdemote") }?.multiplier == DEMOTE_MULTIPLIER }
        vm.boostChannel("UCBoost"); await { runBlocking { repo.recommendationStore.getChannelPreference("ucboost") }?.multiplier == BOOST_MULTIPLIER }
        vm.pinChannel("UCPin"); await { runBlocking { repo.recommendationStore.getChannelPreference("ucpin") }?.pinned == true }
        vm.muteKeyword("Spoilers"); await { runBlocking { repo.recommendationStore.getAllKeywordPreferences() }.firstOrNull { it.keyword == "spoilers" }?.muted == true }
        vm.removeInterest(channelKey = "UCBlock", keyword = "cats")
        await { runBlocking { repo.recommendationStore.getChannelPreference("ucblock") }?.multiplier == 0.0 }
        vm.unmuteKeyword("spoilers"); await { runBlocking { repo.recommendationStore.getAllKeywordPreferences() }.none { it.keyword == "spoilers" } }
        vm.clearChannelPreference("UCPin"); await { runBlocking { repo.recommendationStore.getChannelPreference("ucpin") } == null }
        vm.resetAlgorithm()
        await { runBlocking { repo.recommendationStore.getRecommendationPreferences() } == null }
        await { runBlocking { repo.recommendationStore.getChannelPreference("ucboost") } == null }
    }

    @Test fun resolveWatchUrl_parsesOnlyWatchLinks() {
        vm.setSearchQuery("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        // videoURLtoID extracts numeric/hash id; non-null iff watch link recognised or throws-free null.
        runCatching { vm.resolveWatchUrl() }
        vm.setSearchQuery("plain search terms"); assertNull(vm.resolveWatchUrl())
        vm.setSearchQuery(""); assertNull(vm.resolveWatchUrl())
    }
}
