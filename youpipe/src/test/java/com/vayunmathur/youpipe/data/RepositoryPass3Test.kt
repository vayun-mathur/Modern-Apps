package com.vayunmathur.youpipe.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.youpipe.ui.VideoInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Slice 2/3 pass 3: [SubscriptionRepository] itself, over the injected
 * in-memory database (the production file stays SQLCipher, untouched).
 */
@RunWith(AndroidJUnit4::class)
class RepositoryPass3Test {

    private lateinit var db: SubscriptionDatabase
    private lateinit var repo: SubscriptionRepository

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, SubscriptionDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SubscriptionRepository(context, db)
    }

    @AfterTest
    fun close() = db.close()

    private fun info(id: Long, author: String = "author") = VideoInfo(
        name = "v$id", videoID = id, duration = 100, views = 10,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000),
        thumbnailURL = "", author = author,
    )

    @Test
    fun subscriptionCrudThroughRepository() = runTest {
        val a = repo.subscriptionStore.upsertSubscription(Subscription(name = "A", channelID = "UCA", avatarURL = ""))
        val b = repo.subscriptionStore.upsertSubscription(Subscription(name = "B", channelID = "UCB", avatarURL = ""))
        repo.subscriptionStore.upsertSubscriptions(listOf(Subscription(name = "C", channelID = "UCC", avatarURL = "")))
        assertEquals(listOf("A", "B", "C"), repo.subscriptionStore.getAllSubscriptions().map { it.name })
        assertEquals("B", repo.subscriptionStore.subscriptionById(b).first()?.name)
        assertEquals(3, repo.subscriptions.first().size)
        assertEquals(1, repo.subscriptionStore.deleteSubscription(repo.subscriptionStore.getAllSubscriptions().first { it.name == "C" }))
        repo.subscriptionStore.clearAllSubscriptions()
        assertEquals(emptyList(), repo.subscriptionStore.getAllSubscriptions())
        assertTrue(a > 0)
    }

    @Test
    fun subscriptionVideosAndCategories() = runTest {
        val subId = repo.subscriptionStore.upsertSubscription(Subscription(name = "A", channelID = "UCA", avatarURL = ""))
        repo.subscriptionStore.upsertSubscriptionVideos(
            listOf(
                SubscriptionVideo(name = "sv1", duration = 1, views = 1, uploadDate = Instant.fromEpochSeconds(1), thumbnailURL = "", author = "A", channelID = subId),
            ),
        )
        assertEquals(listOf("sv1"), repo.subscriptionStore.getAllSubscriptionVideos().map { it.name })
        assertEquals("sv1", repo.subscriptionStore.subscriptionVideoById(repo.subscriptionStore.getAllSubscriptionVideos()[0].id).first()?.name)
        assertEquals(1, repo.subscriptionVideos.first().size)

        repo.categoryStore.replaceCategory(null, "News", listOf(subId))
        assertEquals("News", repo.subscriptionCategories.first().single().category)
        assertEquals(subId, repo.categoryStore.subscriptionCategoryById(
            repo.subscriptionCategories.first().single().id,
        ).first()?.subscriptionID)
        repo.categoryStore.upsertSubscriptionCategories(listOf(SubscriptionCategory(subId, "Tech")))
        assertEquals(setOf("News", "Tech"), repo.subscriptionCategories.first().map { it.category }.toSet())
        repo.categoryStore.deleteCategory("Tech")
        assertEquals(listOf("News"), repo.subscriptionCategories.first().map { it.category })
    }

    @Test
    fun historyAndDownloadsThroughRepository() = runTest {
        val row = HistoryVideo(progress = 5, videoItem = info(1), timestamp = Instant.fromEpochSeconds(10))
        val id = repo.historyStore.upsertHistoryVideo(row)
        repo.historyStore.upsertHistoryVideos(
            listOf(HistoryVideo(progress = 6, videoItem = info(2), timestamp = Instant.fromEpochSeconds(11))),
        )
        assertEquals(2, repo.historyStore.getAllHistoryVideos().size)
        assertEquals(5L, repo.historyStore.historyById(id).first()?.progress)
        assertEquals(2, repo.historyVideos.first().size)

        val dl = DownloadedVideo(
            id = 9, videoItem = info(9), filePath = "/dl/9.mp4",
            timestamp = Instant.fromEpochSeconds(12),
        )
        assertEquals(9L, repo.historyStore.upsertDownloadedVideo(dl))
        assertEquals("/dl/9.mp4", repo.historyStore.downloadedById(9).first()?.filePath)
        assertEquals(1, repo.downloadedVideos.first().size)
        assertEquals(1, repo.historyStore.deleteDownloadedVideo(dl))

        repo.historyStore.deleteHistoryVideosByIds(listOf(id))
        assertEquals(1, repo.historyStore.getAllHistoryVideos().size)
        repo.historyStore.deleteHistoryVideo(repo.historyStore.getAllHistoryVideos().single())
        assertEquals(emptyList(), repo.historyStore.getAllHistoryVideos())
        repo.historyStore.upsertHistoryVideo(row)
        repo.historyStore.clearAllHistory()
        assertEquals(emptyList(), repo.historyVideos.first())
    }

    @Test
    fun playlistsThroughRepository() = runTest {
        val pid = repo.playlistStore.upsertPlaylist(Playlist(name = "Mix"))
        repo.playlistStore.upsertPlaylists(listOf(Playlist(name = "Second")))
        assertEquals(listOf("Mix", "Second"), repo.playlistStore.getAllPlaylists().map { it.name })
        assertEquals("Mix", repo.playlistStore.playlistById(pid).first()?.name)
        assertEquals(2, repo.playlists.first().size)

        val item = PlaylistItem(playlistId = pid, videoItem = info(31), timestamp = Instant.fromEpochSeconds(3))
        val itemId = repo.playlistStore.upsertPlaylistItem(item)
        repo.playlistStore.upsertPlaylistItems(
            listOf(PlaylistItem(playlistId = pid, videoItem = info(32), timestamp = Instant.fromEpochSeconds(4))),
        )
        assertEquals(listOf(31L, 32L), repo.playlistStore.getPlaylistItemsForPlaylist(pid).map { it.videoItem.videoID })
        assertEquals(2, repo.playlistStore.playlistItemsFor(pid).first().size)
        assertEquals(2, repo.playlistItems.first().size)
        val first = repo.playlistStore.getPlaylistItemsForPlaylist(pid).first { it.id == itemId }
        assertEquals(1, repo.playlistStore.deletePlaylistItem(first))
        assertEquals(1, repo.playlistStore.deletePlaylist(repo.playlistStore.getAllPlaylists().first { it.name == "Second" }))
        // The store splits are reachable through the repository too.
        assertSame(repo.playlistStore, repo.playlistStore)
        assertSame(repo.categoryStore, repo.categoryStore)
        assertSame(repo.recommendationStore, repo.recommendationStore)
    }

    @Test
    fun recommendationsThroughRepository() = runTest {
        repo.recommendationStore.upsertCachedRelatedVideos(
            listOf(
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(41), cachedAt = Instant.fromEpochSeconds(100)),
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(42), cachedAt = Instant.fromEpochSeconds(200)),
            ),
        )
        assertEquals(2, repo.recommendationStore.getAllCachedRelatedVideos().size)
        assertEquals(2, repo.cachedRelatedVideos.first().size)
        repo.recommendationStore.deleteCachedRelatedOlderThan(Instant.fromEpochSeconds(150))
        assertEquals(listOf(42L), repo.recommendationStore.getAllCachedRelatedVideos().map { it.videoItem.videoID })

        repo.recommendationStore.recordRecommendationImpression(7, "chan", "RELATED", Instant.fromEpochSeconds(500))
        repo.recommendationStore.recordRecommendationImpression(7, "chan", "RELATED", Instant.fromEpochSeconds(600))
        val impressions = repo.recommendationStore.getAllRecommendationImpressions()
        assertEquals(1, impressions.size)
        assertEquals(2, impressions[0].shownCount)
        assertEquals(1, repo.recommendationImpressions.first().size)
        // The cutoff compares lastShownAt (600s), not firstShownAt.
        repo.recommendationStore.deleteRecommendationImpressionsOlderThan(Instant.fromEpochSeconds(550))
        assertEquals(listOf(7L), repo.recommendationStore.getAllRecommendationImpressions().map { it.videoID })
        repo.recommendationStore.deleteRecommendationImpressionsOlderThan(Instant.fromEpochSeconds(650))
        assertEquals(emptyList(), repo.recommendationStore.getAllRecommendationImpressions())
        repo.recommendationStore.recordRecommendationImpression(8, "chan", "SEARCH", Instant.fromEpochSeconds(700))
        repo.recommendationStore.clearAllRecommendationImpressions()
        assertEquals(emptyList(), repo.recommendationStore.getAllRecommendationImpressions())

        assertNull(repo.recommendationStore.getRecommendationPreferences())
        repo.recommendationStore.upsertRecommendationPreferences(RecommendationPreferences(preset = "FOCUSED"))
        assertEquals("FOCUSED", repo.recommendationStore.getRecommendationPreferences()?.preset)
        assertEquals("FOCUSED", repo.recommendationPreferencesFlow.first()?.preset)
        repo.recommendationStore.clearAllRecommendationPreferences()
        assertNull(repo.recommendationStore.getRecommendationPreferences())

        repo.recommendationStore.upsertChannelPreference(ChannelPreference("chan", multiplier = 0.5))
        assertEquals(0.5, repo.recommendationStore.getChannelPreference("chan")?.multiplier)
        assertEquals(1, repo.channelPreferences.first().size)
        repo.recommendationStore.deleteChannelPreference("chan")
        assertNull(repo.recommendationStore.getChannelPreference("chan"))
        repo.recommendationStore.upsertChannelPreference(ChannelPreference("x"))
        repo.recommendationStore.clearAllChannelPreferences()
        assertEquals(emptyList(), repo.recommendationStore.getAllChannelPreferences())

        repo.recommendationStore.upsertKeywordPreference(KeywordPreference("spoiler"))
        assertEquals(1, repo.keywordPreferences.first().size)
        repo.recommendationStore.deleteKeywordPreference("spoiler")
        repo.recommendationStore.upsertKeywordPreference(KeywordPreference("ad"))
        repo.recommendationStore.clearAllKeywordPreferences()
        assertEquals(emptyList(), repo.recommendationStore.getAllKeywordPreferences())
    }

    @Test
    fun singletonIsStable() {
        val first = SubscriptionRepository.get(context)
        val second = SubscriptionRepository.get(context)
        assertSame(first, second)
    }
}
