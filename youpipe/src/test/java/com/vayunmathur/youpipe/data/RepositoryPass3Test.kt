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
        val a = repo.upsertSubscription(Subscription(name = "A", channelID = "UCA", avatarURL = ""))
        val b = repo.upsertSubscription(Subscription(name = "B", channelID = "UCB", avatarURL = ""))
        repo.upsertSubscriptions(listOf(Subscription(name = "C", channelID = "UCC", avatarURL = "")))
        assertEquals(listOf("A", "B", "C"), repo.getAllSubscriptions().map { it.name })
        assertEquals("B", repo.subscriptionById(b).first()?.name)
        assertEquals(3, repo.subscriptions.first().size)
        assertEquals(1, repo.deleteSubscription(repo.getAllSubscriptions().first { it.name == "C" }))
        repo.clearAllSubscriptions()
        assertEquals(emptyList(), repo.getAllSubscriptions())
        assertTrue(a > 0)
    }

    @Test
    fun subscriptionVideosAndCategories() = runTest {
        val subId = repo.upsertSubscription(Subscription(name = "A", channelID = "UCA", avatarURL = ""))
        repo.upsertSubscriptionVideos(
            listOf(
                SubscriptionVideo(name = "sv1", duration = 1, views = 1, uploadDate = Instant.fromEpochSeconds(1), thumbnailURL = "", author = "A", channelID = subId),
            ),
        )
        assertEquals(listOf("sv1"), repo.getAllSubscriptionVideos().map { it.name })
        assertEquals("sv1", repo.subscriptionVideoById(repo.getAllSubscriptionVideos()[0].id).first()?.name)
        assertEquals(1, repo.subscriptionVideos.first().size)

        repo.replaceCategory(null, "News", listOf(subId))
        assertEquals("News", repo.subscriptionCategories.first().single().category)
        assertEquals(subId, repo.subscriptionCategoryById(
            repo.subscriptionCategories.first().single().id,
        ).first()?.subscriptionID)
        repo.upsertSubscriptionCategories(listOf(SubscriptionCategory(subId, "Tech")))
        assertEquals(setOf("News", "Tech"), repo.subscriptionCategories.first().map { it.category }.toSet())
        repo.deleteCategory("Tech")
        assertEquals(listOf("News"), repo.subscriptionCategories.first().map { it.category })
    }

    @Test
    fun historyAndDownloadsThroughRepository() = runTest {
        val row = HistoryVideo(progress = 5, videoItem = info(1), timestamp = Instant.fromEpochSeconds(10))
        val id = repo.upsertHistoryVideo(row)
        repo.upsertHistoryVideos(
            listOf(HistoryVideo(progress = 6, videoItem = info(2), timestamp = Instant.fromEpochSeconds(11))),
        )
        assertEquals(2, repo.getAllHistoryVideos().size)
        assertEquals(5L, repo.historyById(id).first()?.progress)
        assertEquals(2, repo.historyVideos.first().size)

        val dl = DownloadedVideo(
            id = 9, videoItem = info(9), filePath = "/dl/9.mp4",
            timestamp = Instant.fromEpochSeconds(12),
        )
        assertEquals(9L, repo.upsertDownloadedVideo(dl))
        assertEquals("/dl/9.mp4", repo.downloadedById(9).first()?.filePath)
        assertEquals(1, repo.downloadedVideos.first().size)
        assertEquals(1, repo.deleteDownloadedVideo(dl))

        repo.deleteHistoryVideosByIds(listOf(id))
        assertEquals(1, repo.getAllHistoryVideos().size)
        repo.deleteHistoryVideo(repo.getAllHistoryVideos().single())
        assertEquals(emptyList(), repo.getAllHistoryVideos())
        repo.upsertHistoryVideo(row)
        repo.clearAllHistory()
        assertEquals(emptyList(), repo.historyVideos.first())
    }

    @Test
    fun playlistsThroughRepository() = runTest {
        val pid = repo.upsertPlaylist(Playlist(name = "Mix"))
        repo.upsertPlaylists(listOf(Playlist(name = "Second")))
        assertEquals(listOf("Mix", "Second"), repo.getAllPlaylists().map { it.name })
        assertEquals("Mix", repo.playlistById(pid).first()?.name)
        assertEquals(2, repo.playlists.first().size)

        val item = PlaylistItem(playlistId = pid, videoItem = info(31), timestamp = Instant.fromEpochSeconds(3))
        val itemId = repo.upsertPlaylistItem(item)
        repo.upsertPlaylistItems(
            listOf(PlaylistItem(playlistId = pid, videoItem = info(32), timestamp = Instant.fromEpochSeconds(4))),
        )
        assertEquals(listOf(31L, 32L), repo.getPlaylistItemsForPlaylist(pid).map { it.videoItem.videoID })
        assertEquals(2, repo.playlistItemsFor(pid).first().size)
        assertEquals(2, repo.playlistItems.first().size)
        val first = repo.getPlaylistItemsForPlaylist(pid).first { it.id == itemId }
        assertEquals(1, repo.deletePlaylistItem(first))
        assertEquals(1, repo.deletePlaylist(repo.getAllPlaylists().first { it.name == "Second" }))
        // The store splits are reachable through the repository too.
        assertSame(repo.playlistStore, repo.playlistStore)
        assertSame(repo.categoryStore, repo.categoryStore)
        assertSame(repo.recommendationStore, repo.recommendationStore)
    }

    @Test
    fun recommendationsThroughRepository() = runTest {
        repo.upsertCachedRelatedVideos(
            listOf(
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(41), cachedAt = Instant.fromEpochSeconds(100)),
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(42), cachedAt = Instant.fromEpochSeconds(200)),
            ),
        )
        assertEquals(2, repo.getAllCachedRelatedVideos().size)
        assertEquals(2, repo.cachedRelatedVideos.first().size)
        repo.deleteCachedRelatedOlderThan(Instant.fromEpochSeconds(150))
        assertEquals(listOf(42L), repo.getAllCachedRelatedVideos().map { it.videoItem.videoID })

        repo.recordRecommendationImpression(7, "chan", "RELATED", Instant.fromEpochSeconds(500))
        repo.recordRecommendationImpression(7, "chan", "RELATED", Instant.fromEpochSeconds(600))
        val impressions = repo.getAllRecommendationImpressions()
        assertEquals(1, impressions.size)
        assertEquals(2, impressions[0].shownCount)
        assertEquals(1, repo.recommendationImpressions.first().size)
        // The cutoff compares lastShownAt (600s), not firstShownAt.
        repo.deleteRecommendationImpressionsOlderThan(Instant.fromEpochSeconds(550))
        assertEquals(listOf(7L), repo.getAllRecommendationImpressions().map { it.videoID })
        repo.deleteRecommendationImpressionsOlderThan(Instant.fromEpochSeconds(650))
        assertEquals(emptyList(), repo.getAllRecommendationImpressions())
        repo.recordRecommendationImpression(8, "chan", "SEARCH", Instant.fromEpochSeconds(700))
        repo.clearAllRecommendationImpressions()
        assertEquals(emptyList(), repo.getAllRecommendationImpressions())

        assertNull(repo.getRecommendationPreferences())
        repo.upsertRecommendationPreferences(RecommendationPreferences(preset = "FOCUSED"))
        assertEquals("FOCUSED", repo.getRecommendationPreferences()?.preset)
        assertEquals("FOCUSED", repo.recommendationPreferencesFlow.first()?.preset)
        repo.clearAllRecommendationPreferences()
        assertNull(repo.getRecommendationPreferences())

        repo.upsertChannelPreference(ChannelPreference("chan", multiplier = 0.5))
        assertEquals(0.5, repo.getChannelPreference("chan")?.multiplier)
        assertEquals(1, repo.channelPreferences.first().size)
        repo.deleteChannelPreference("chan")
        assertNull(repo.getChannelPreference("chan"))
        repo.upsertChannelPreference(ChannelPreference("x"))
        repo.clearAllChannelPreferences()
        assertEquals(emptyList(), repo.getAllChannelPreferences())

        repo.upsertKeywordPreference(KeywordPreference("spoiler"))
        assertEquals(1, repo.keywordPreferences.first().size)
        repo.deleteKeywordPreference("spoiler")
        repo.upsertKeywordPreference(KeywordPreference("ad"))
        repo.clearAllKeywordPreferences()
        assertEquals(emptyList(), repo.getAllKeywordPreferences())
    }

    @Test
    fun singletonIsStable() {
        val first = SubscriptionRepository.get(context)
        val second = SubscriptionRepository.get(context)
        assertSame(first, second)
    }
}
