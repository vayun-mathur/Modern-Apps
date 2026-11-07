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
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Slice 2/3 pass 3: the DAOs the earlier slice pass did not reach
 * (Subscription, SubscriptionVideo, categories, playlists, recommendation
 * bookkeeping) and the three stores built on them, against an in-memory DB.
 */
@RunWith(AndroidJUnit4::class)
class StoresPass3Test {

    private lateinit var db: SubscriptionDatabase

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SubscriptionDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @AfterTest
    fun close() = db.close()

    private fun info(id: Long, author: String = "author") = VideoInfo(
        name = "v$id", videoID = id, duration = 100, views = 10,
        uploadDate = Instant.fromEpochSeconds(1_700_000_000),
        thumbnailURL = "", author = author,
    )

    private fun subscription(id: Long = 0, name: String = "Chan") = Subscription(
        id = id, name = name, channelID = "UC$id", avatarURL = "",
    )

    @Test
    fun subscriptionDao_crudAndFlows() = runTest {
        val dao = db.subscriptionDao()
        val idA = dao.upsert(subscription(name = "Alpha"))
        val idB = dao.upsert(subscription(name = "Beta"))
        assertTrue(idA > 0 && idB > idA)
        assertEquals(listOf("Alpha", "Beta"), dao.getAll().map { it.name })
        assertEquals("Beta", dao.getByIdFlow(idB).first()?.name)
        assertEquals(2, dao.getAllFlow().first().size)
        dao.upsertAll(listOf(subscription(name = "Gamma")))
        assertEquals(3, dao.getAll().size)
        assertEquals(1, dao.delete(dao.getAll().first { it.name == "Gamma" }))
        dao.clearAll()
        assertEquals(emptyList(), dao.getAll())
        assertNull(dao.getByIdFlow(idA).first())
    }

    @Test
    fun subscriptionVideoDao_upsertAllAndRead() = runTest {
        // SubscriptionVideo.channelID is an FK to Subscription.id.
        val parent = db.subscriptionDao().upsert(subscription(name = "Parent"))
        val dao = db.subscriptionVideoDao()
        dao.upsertAll(
            listOf(
                SubscriptionVideo(name = "one", duration = 10, views = 5, uploadDate = Instant.fromEpochSeconds(1), thumbnailURL = "", author = "a", channelID = parent),
                SubscriptionVideo(name = "two", duration = 20, views = 6, uploadDate = Instant.fromEpochSeconds(2), thumbnailURL = "", author = "a", channelID = parent),
            ),
        )
        val all = dao.getAll()
        assertEquals(listOf("one", "two"), all.map { it.name })
        assertEquals("two", dao.getByIdFlow(all[1].id).first()?.name)
        assertEquals(2, dao.getAllFlow().first().size)
    }

    @Test
    fun historyDao_deleteByIdsAndClear() = runTest {
        val dao = db.historyVideoDao()
        dao.upsertAll(
            listOf(
                HistoryVideo(progress = 10, videoItem = info(1), timestamp = Instant.fromEpochSeconds(1)),
                HistoryVideo(progress = 20, videoItem = info(2), timestamp = Instant.fromEpochSeconds(2)),
                HistoryVideo(progress = 30, videoItem = info(3), timestamp = Instant.fromEpochSeconds(3)),
            ),
        )
        assertEquals(3, dao.getAll().size)
        val victim = dao.getAll().first { it.videoItem.videoID == 2L }
        dao.deleteByIds(listOf(victim.id))
        assertEquals(listOf(1L, 3L), dao.getAll().map { it.videoItem.videoID })
        dao.delete(dao.getAll().first())
        assertEquals(1, dao.getAll().size)
        dao.clearAll()
        assertEquals(emptyList(), dao.getAllFlow().first())
    }

    @Test
    fun categoryStore_replaceRenameAndDelete() = runTest {
        val sub = db.subscriptionDao()
        val a = sub.upsert(subscription(name = "A"))
        val b = sub.upsert(subscription(name = "B"))
        val store = SubscriptionCategoryStore(db.subscriptionCategoryDao())

        store.replaceCategory(null, "News", listOf(a, b))
        var cats = db.subscriptionCategoryDao().getAllFlow().first()
        assertEquals(setOf(a, b), cats.map { it.subscriptionID }.toSet())
        assertTrue(cats.all { it.category == "News" })

        // Rename: the old category's rows go away, the new set replaces them.
        store.replaceCategory("News", "Tech", listOf(b))
        cats = db.subscriptionCategoryDao().getAllFlow().first()
        assertEquals(1, cats.size)
        assertEquals("Tech", cats[0].category)
        assertEquals(b, cats[0].subscriptionID)

        store.deleteCategory("Tech")
        assertEquals(emptyList(), db.subscriptionCategoryDao().getAllFlow().first())

        store.upsertSubscriptionCategories(listOf(SubscriptionCategory(a, "Manual")))
        assertEquals("Manual", db.subscriptionCategoryDao().getAllFlow().first().single().category)
    }

    @Test
    fun playlistStore_fullLifecycle() = runTest {
        val store = PlaylistStore(db.playlistDao(), db.playlistItemDao())
        val watchLater = store.upsertPlaylist(Playlist(name = "Watch later", mandatory = true))
        val music = store.upsertPlaylist(Playlist(name = "Music"))
        store.upsertPlaylists(listOf(Playlist(name = "Extra")))
        assertEquals(listOf("Watch later", "Music", "Extra"), store.getAllPlaylists().map { it.name })

        val itemId = store.upsertPlaylistItem(
            PlaylistItem(playlistId = music, videoItem = info(11), timestamp = Instant.fromEpochSeconds(5)),
        )
        store.upsertPlaylistItems(
            listOf(PlaylistItem(playlistId = music, videoItem = info(12), timestamp = Instant.fromEpochSeconds(6))),
        )
        val items = store.getPlaylistItemsForPlaylist(music)
        assertEquals(listOf(11L, 12L), items.map { it.videoItem.videoID })
        assertEquals(itemId, items.first { it.videoItem.videoID == 11L }.id)

        store.deletePlaylistItemByVideo(music, 11)
        assertEquals(listOf(12L), store.getPlaylistItemsForPlaylist(music).map { it.videoItem.videoID })
        val remaining = store.getPlaylistItemsForPlaylist(music).single()
        assertEquals(1, store.deletePlaylistItem(remaining))

        // Deleting a playlist cascades to its items (FK) but leaves others.
        store.upsertPlaylistItem(
            PlaylistItem(playlistId = music, videoItem = info(13), timestamp = Instant.fromEpochSeconds(7)),
        )
        val musicPlaylist = store.getAllPlaylists().first { it.name == "Music" }
        assertEquals(1, store.deletePlaylist(musicPlaylist))
        assertEquals(emptyList(), store.getPlaylistItemsForPlaylist(music))
        assertEquals(listOf("Watch later", "Extra"), store.getAllPlaylists().map { it.name })
        assertEquals(watchLater, db.playlistDao().getByIdFlow(watchLater).first()?.id)
    }

    @Test
    fun recommendationStore_impressionsPreferencesAndOverrides() = runTest {
        val store = RecommendationStore(
            db.cachedRelatedVideoDao(),
            db.recommendationImpressionDao(),
            db.recommendationPreferencesDao(),
            db.channelPreferenceDao(),
            db.keywordPreferenceDao(),
        )
        val t0 = Instant.fromEpochSeconds(1_000)
        val t1 = Instant.fromEpochSeconds(2_000)

        // Impressions accumulate on conflict.
        store.recordRecommendationImpression(42, "chan", "RELATED", t0)
        store.recordRecommendationImpression(42, "chan", "RELATED", t1)
        store.recordRecommendationImpression(43, "chan", "TRENDING", t0)
        val impressions = store.getAllRecommendationImpressions()
        assertEquals(2, impressions.size)
        val imp42 = impressions.first { it.videoID == 42L }
        assertEquals(2, imp42.shownCount)
        assertEquals(t0, imp42.firstShownAt)
        assertEquals(t1, imp42.lastShownAt)

        store.deleteRecommendationImpressionsOlderThan(Instant.fromEpochSeconds(1_500))
        assertEquals(listOf(42L), store.getAllRecommendationImpressions().map { it.videoID })
        store.clearAllRecommendationImpressions()
        assertEquals(emptyList(), store.getAllRecommendationImpressions())

        // Preferences are a single row with defaults until written.
        assertNull(store.getRecommendationPreferences())
        store.upsertRecommendationPreferences(
            RecommendationPreferences(preset = "DISCOVERY", discoveryFamiliar = 0.9f, hideShorts = true),
        )
        val prefs = store.getRecommendationPreferences()
        assertEquals("DISCOVERY", prefs?.preset)
        assertEquals(0.9f, prefs?.discoveryFamiliar)
        assertEquals(true, prefs?.hideShorts)
        store.clearAllRecommendationPreferences()
        assertNull(store.getRecommendationPreferences())

        // Channel and keyword overrides.
        store.upsertChannelPreference(ChannelPreference("chan", multiplier = 2.0, pinned = true))
        store.upsertChannelPreference(ChannelPreference("other", blocked = true))
        assertEquals(2.0, store.getChannelPreference("chan")?.multiplier)
        assertEquals(true, store.getChannelPreference("other")?.blocked)
        assertEquals(2, store.getAllChannelPreferences().size)
        store.deleteChannelPreference("chan")
        assertNull(store.getChannelPreference("chan"))
        store.clearAllChannelPreferences()
        assertEquals(emptyList(), store.getAllChannelPreferences())

        store.upsertKeywordPreference(KeywordPreference("spoiler", muted = true))
        assertEquals(listOf("spoiler"), store.getAllKeywordPreferences().map { it.keyword })
        store.deleteKeywordPreference("spoiler")
        store.upsertKeywordPreference(KeywordPreference("news"))
        store.clearAllKeywordPreferences()
        assertEquals(emptyList(), store.getAllKeywordPreferences())

        // Cached related videos with cutoff.
        store.upsertCachedRelatedVideos(
            listOf(
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(21), cachedAt = t0),
                CachedRelatedVideo(sourceVideoID = 1, videoItem = info(22), cachedAt = t1),
            ),
        )
        assertEquals(2, store.getAllCachedRelatedVideos().size)
        store.deleteCachedRelatedOlderThan(Instant.fromEpochSeconds(1_500))
        assertEquals(listOf(22L), store.getAllCachedRelatedVideos().map { it.videoItem.videoID })
    }

    @Test
    fun migrations_areTheSevenStepChain() {
        val migrations = SubscriptionDatabase.migrations
        assertEquals(6, migrations.size)
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7),
            migrations.map { it.startVersion to it.endVersion })
    }
}
