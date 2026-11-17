package com.vayunmathur.youpipe.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for all YouPipe persisted data.
 *
 * Owns the one [SubscriptionDatabase] instance (via [RoomRepository]) and is the
 * only place the 12 DAOs are touched. The [com.vayunmathur.youpipe.platform.YouPipeViewModel],
 * [com.vayunmathur.youpipe.platform.DownloadWorker], and
 * [com.vayunmathur.youpipe.platform.SubscriptionFetchTask] all read/write through here
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
    private val recommendationPreferencesDao: RecommendationPreferencesDao
        get() = database.recommendationPreferencesDao()
    private val channelPreferenceDao: ChannelPreferenceDao get() = database.channelPreferenceDao()
    private val keywordPreferenceDao: KeywordPreferenceDao get() = database.keywordPreferenceDao()
    private val playlistDao: PlaylistDao get() = database.playlistDao()
    private val playlistItemDao: PlaylistItemDao get() = database.playlistItemDao()

    /** Category writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    val categoryStore by lazy { SubscriptionCategoryStore(subscriptionCategoryDao) }

    /** Subscription reads/writes (split out: TooManyFunctions cap). */
    val subscriptionStore by lazy { SubscriptionStore(subscriptionDao, subscriptionVideoDao) }

    /** History and download reads/writes (split out: TooManyFunctions cap). */
    val historyStore by lazy { HistoryStore(historyVideoDao, downloadedVideoDao) }

    /** Playlist writes (split out: TooManyFunctions cap). Same module; behavior identical. */
    val playlistStore by lazy { PlaylistStore(playlistDao, playlistItemDao) }

    /**
     * Recommendation reads/writes (split out: TooManyFunctions cap).
     * Same module; behavior identical.
     */
    val recommendationStore by lazy {
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

    // All writes live on the stores above (TooManyFunctions cap); call sites
    // use `repository.<store>.<fn>` directly. Behavior is identical.

    /** Shared constants and helpers for SubscriptionRepository. */
    companion object {
        @Volatile
        private var instance: SubscriptionRepository? = null

        fun get(context: Context): SubscriptionRepository =
            instance ?: synchronized(this) {
                instance ?: SubscriptionRepository(context).also { instance = it }
            }
    }
}
