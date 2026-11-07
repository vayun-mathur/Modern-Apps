package com.vayunmathur.youpipe.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all YouPipe persisted data.
 *
 * Owns the one [SubscriptionDatabase] instance (via [RoomRepository]) and is the
 * only place the 12 DAOs are touched. The [com.vayunmathur.youpipe.util.YouPipeViewModel],
 * [com.vayunmathur.youpipe.util.DownloadWorker], and
 * [com.vayunmathur.youpipe.util.SubscriptionFetchTask] all read/write through here
 * so they share one live, invalidation-backed view of the data.
 *
 * Read access is exposed as cold [Flow]s (consumers apply `stateIn`/`map` as before).
 * Writes are `suspend` wrappers over the existing atomic DAO queries, so no caller
 * ever holds a DAO directly.
 *
 * Uses the default `passwords-db` file name (same as all three previous
 * `buildDatabase<SubscriptionDatabase>()` call sites — MainActivity, DownloadWorker,
 * SubscriptionFetchTask — so the same DB file continues to be opened).
 */
class SubscriptionRepository internal constructor(
    context: Context,
    /**
     * Test seam: a database built by the caller (e.g. in-memory) to run the
     * repository against. Null in production, where the owned lazy [db] —
     * the SQLCipher file every app database is — is used exactly as before.
     */
    private val injectedDatabase: SubscriptionDatabase? = null,
) : RoomRepository<SubscriptionDatabase>(context, SubscriptionDatabase::class) {

    /** The database every DAO below comes from: injected in tests, else [db]. */
    private val database: SubscriptionDatabase get() = injectedDatabase ?: db

    private val subscriptionDao: SubscriptionDao get() = database.subscriptionDao()
    private val subscriptionCategoryDao: SubscriptionCategoryDao get() = database.subscriptionCategoryDao()
    private val subscriptionVideoDao: SubscriptionVideoDao get() = database.subscriptionVideoDao()
    private val historyVideoDao: HistoryVideoDao get() = database.historyVideoDao()
    private val downloadedVideoDao: DownloadedVideoDao get() = database.downloadedVideoDao()
    private val cachedRelatedVideoDao: CachedRelatedVideoDao get() = database.cachedRelatedVideoDao()
    private val recommendationImpressionDao: RecommendationImpressionDao get() = database.recommendationImpressionDao()
    private val recommendationPreferencesDao: RecommendationPreferencesDao get() = database.recommendationPreferencesDao()
    private val channelPreferenceDao: ChannelPreferenceDao get() = database.channelPreferenceDao()
    private val keywordPreferenceDao: KeywordPreferenceDao get() = database.keywordPreferenceDao()
    private val playlistDao: PlaylistDao get() = database.playlistDao()
    private val playlistItemDao: PlaylistItemDao get() = database.playlistItemDao()

    /** Category writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    internal val categoryStore by lazy { SubscriptionCategoryStore(subscriptionCategoryDao) }

    /** Playlist writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    internal val playlistStore by lazy { PlaylistStore(playlistDao, playlistItemDao) }

    /**
     * Recommendation reads/writes (split out: TooManyFunctions cap).
     * Same module; behavior identical.
     */
    internal val recommendationStore by lazy {
        RecommendationStore(
            cachedRelatedVideoDao,
            recommendationImpressionDao,
            recommendationPreferencesDao,
            channelPreferenceDao,
            keywordPreferenceDao,
        )
    }

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val subscriptions: Flow<List<Subscription>> get() = subscriptionDao.getAllFlow()
    val subscriptionCategories: Flow<List<SubscriptionCategory>> get() = subscriptionCategoryDao.getAllFlow()
    val subscriptionVideos: Flow<List<SubscriptionVideo>> get() = subscriptionVideoDao.getAllFlow()
    val historyVideos: Flow<List<HistoryVideo>> get() = historyVideoDao.getAllFlow()
    val downloadedVideos: Flow<List<DownloadedVideo>> get() = downloadedVideoDao.getAllFlow()
    val playlists: Flow<List<Playlist>> get() = playlistDao.getAllFlow()
    val playlistItems: Flow<List<PlaylistItem>> get() = playlistItemDao.getAllFlow()
    val cachedRelatedVideos: Flow<List<CachedRelatedVideo>> get() = cachedRelatedVideoDao.getAllFlow()
    val recommendationImpressions: Flow<List<RecommendationImpression>> get() = recommendationImpressionDao.getAllFlow()
    val recommendationPreferencesFlow: Flow<RecommendationPreferences?> get() = recommendationPreferencesDao.getFlow()
    val channelPreferences: Flow<List<ChannelPreference>> get() = channelPreferenceDao.getAllFlow()
    val keywordPreferences: Flow<List<KeywordPreference>> get() = keywordPreferenceDao.getAllFlow()

    // By-id flows
    fun historyById(id: Long): Flow<HistoryVideo?> = historyVideoDao.getByIdFlow(id)
    fun downloadedById(id: Long): Flow<DownloadedVideo?> = downloadedVideoDao.getByIdFlow(id)
    fun playlistById(id: Long): Flow<Playlist?> = playlistDao.getByIdFlow(id)
    fun subscriptionById(id: Long): Flow<Subscription?> = subscriptionDao.getByIdFlow(id)
    fun subscriptionVideoById(id: Long): Flow<SubscriptionVideo?> = subscriptionVideoDao.getByIdFlow(id)
    fun subscriptionCategoryById(id: Long): Flow<SubscriptionCategory?> = subscriptionCategoryDao.getByIdFlow(id)
    fun playlistItemsFor(playlistId: Long): Flow<List<PlaylistItem>> = playlistItemDao.getForPlaylistFlow(playlistId)

    // ------------------------------------------------------------------
    // Subscription
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptions(): List<Subscription> = subscriptionDao.getAll()
    suspend fun upsertSubscription(value: Subscription): Long = subscriptionDao.upsert(value)
    suspend fun upsertSubscriptions(values: List<Subscription>) = subscriptionDao.upsertAll(values)
    suspend fun deleteSubscription(value: Subscription): Int = subscriptionDao.delete(value)
    suspend fun clearAllSubscriptions() = subscriptionDao.clearAll()

    // Category writes live on [categoryStore]; recommendation reads/writes on
    // [recommendationStore]; playlist writes on [playlistStore] (TooManyFunctions cap).
    // Call sites use `repository.<store>.<fn>` directly; behavior is identical.

    // ------------------------------------------------------------------
    // SubscriptionVideo
    // ------------------------------------------------------------------

    suspend fun getAllSubscriptionVideos(): List<SubscriptionVideo> = subscriptionVideoDao.getAll()
    suspend fun upsertSubscriptionVideos(values: List<SubscriptionVideo>) = subscriptionVideoDao.upsertAll(values)

    // ------------------------------------------------------------------
    // Category (delegates to SubscriptionCategoryStore; kept here so existing
    // call sites using `repository.*` keep compiling)
    // ------------------------------------------------------------------

    suspend fun replaceCategory(originalCategoryName: String?, categoryName: String, ids: List<Long>) =
        categoryStore.replaceCategory(originalCategoryName, categoryName, ids)

    suspend fun deleteCategory(categoryName: String) = categoryStore.deleteCategory(categoryName)

    suspend fun upsertSubscriptionCategories(items: List<SubscriptionCategory>) =
        categoryStore.upsertSubscriptionCategories(items)

    // ------------------------------------------------------------------
    // Playlist (delegates to PlaylistStore)
    // ------------------------------------------------------------------

    suspend fun getAllPlaylists(): List<Playlist> = playlistStore.getAllPlaylists()
    suspend fun upsertPlaylist(value: Playlist): Long = playlistStore.upsertPlaylist(value)
    suspend fun upsertPlaylists(values: List<Playlist>) = playlistStore.upsertPlaylists(values)
    suspend fun deletePlaylist(value: Playlist): Int = playlistStore.deletePlaylist(value)
    suspend fun getPlaylistItemsForPlaylist(playlistId: Long): List<PlaylistItem> =
        playlistStore.getPlaylistItemsForPlaylist(playlistId)
    suspend fun upsertPlaylistItem(value: PlaylistItem): Long = playlistStore.upsertPlaylistItem(value)
    suspend fun upsertPlaylistItems(values: List<PlaylistItem>) = playlistStore.upsertPlaylistItems(values)
    suspend fun deletePlaylistItem(value: PlaylistItem): Int = playlistStore.deletePlaylistItem(value)

    // ------------------------------------------------------------------
    // Recommendation (delegates to RecommendationStore)
    // ------------------------------------------------------------------

    suspend fun getAllCachedRelatedVideos(): List<CachedRelatedVideo> =
        recommendationStore.getAllCachedRelatedVideos()
    suspend fun upsertCachedRelatedVideos(values: List<CachedRelatedVideo>) =
        recommendationStore.upsertCachedRelatedVideos(values)
    suspend fun deleteCachedRelatedOlderThan(cutoff: kotlin.time.Instant) =
        recommendationStore.deleteCachedRelatedOlderThan(cutoff)
    suspend fun getAllRecommendationImpressions(): List<RecommendationImpression> =
        recommendationStore.getAllRecommendationImpressions()
    suspend fun recordRecommendationImpression(
        videoID: Long,
        channelKey: String,
        source: String,
        now: kotlin.time.Instant,
    ) = recommendationStore.recordRecommendationImpression(videoID, channelKey, source, now)
    suspend fun deleteRecommendationImpressionsOlderThan(cutoff: kotlin.time.Instant) =
        recommendationStore.deleteRecommendationImpressionsOlderThan(cutoff)
    suspend fun clearAllRecommendationImpressions() = recommendationStore.clearAllRecommendationImpressions()
    suspend fun getRecommendationPreferences(): RecommendationPreferences? =
        recommendationStore.getRecommendationPreferences()
    suspend fun upsertRecommendationPreferences(value: RecommendationPreferences) =
        recommendationStore.upsertRecommendationPreferences(value)
    suspend fun clearAllRecommendationPreferences() = recommendationStore.clearAllRecommendationPreferences()
    suspend fun getAllChannelPreferences(): List<ChannelPreference> =
        recommendationStore.getAllChannelPreferences()
    suspend fun getChannelPreference(channelKey: String): ChannelPreference? =
        recommendationStore.getChannelPreference(channelKey)
    suspend fun upsertChannelPreference(value: ChannelPreference) =
        recommendationStore.upsertChannelPreference(value)
    suspend fun deleteChannelPreference(channelKey: String) =
        recommendationStore.deleteChannelPreference(channelKey)
    suspend fun clearAllChannelPreferences() = recommendationStore.clearAllChannelPreferences()
    suspend fun getAllKeywordPreferences(): List<KeywordPreference> =
        recommendationStore.getAllKeywordPreferences()
    suspend fun upsertKeywordPreference(value: KeywordPreference) =
        recommendationStore.upsertKeywordPreference(value)
    suspend fun deleteKeywordPreference(keyword: String) =
        recommendationStore.deleteKeywordPreference(keyword)
    suspend fun clearAllKeywordPreferences() = recommendationStore.clearAllKeywordPreferences()

    // ------------------------------------------------------------------
    // HistoryVideo
    // ------------------------------------------------------------------

    suspend fun getAllHistoryVideos(): List<HistoryVideo> = historyVideoDao.getAll()
    suspend fun upsertHistoryVideo(value: HistoryVideo): Long = historyVideoDao.upsert(value)
    suspend fun upsertHistoryVideos(values: List<HistoryVideo>) = historyVideoDao.upsertAll(values)
    suspend fun deleteHistoryVideosByIds(ids: List<Long>) = historyVideoDao.deleteByIds(ids)
    suspend fun deleteHistoryVideo(value: HistoryVideo) = historyVideoDao.delete(value)
    suspend fun clearAllHistory() = historyVideoDao.clearAll()

    // ------------------------------------------------------------------
    // DownloadedVideo
    // ------------------------------------------------------------------

    suspend fun upsertDownloadedVideo(value: DownloadedVideo): Long = downloadedVideoDao.upsert(value)
    suspend fun deleteDownloadedVideo(value: DownloadedVideo): Int = downloadedVideoDao.delete(value)

    // (CachedRelatedVideo / RecommendationImpression / RecommendationPreferences /
    // ChannelPreference / KeywordPreference / Playlist / PlaylistItem live on the stores above.)

    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun get(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context).also { instance = it }
            }
    }
}
