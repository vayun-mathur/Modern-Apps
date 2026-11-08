package com.vayunmathur.uniflix.data

import android.content.Context
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for Uniflix persisted state.
 *
 * Owns the one [UniflixDatabase] instance (via [RoomRepository]); ViewModels
 * read/write through here so they share one live view of the data.
 */
class UniflixRepository private constructor(context: Context) :
    RoomRepository<UniflixDatabase>(
        context,
        UniflixDatabase::class,
        "uniflix-db",
        migrations = UniflixDatabase.migrations,
    ) {

    private val watchlistDao: WatchlistDao get() = db.watchlistDao()
    private val continueDao: ContinueWatchingDao get() = db.continueWatchingDao()
    private val listDao: UserListDao get() = db.userListDao()
    private val addonDao: AddonDao get() = db.addonDao()

    val watchlist: Flow<List<WatchlistEntry>> get() = watchlistDao.getAllFlow()
    val continueWatching: Flow<List<ContinueWatchingEntry>> get() = continueDao.getAllFlow()
    val userLists: Flow<List<UserList>> get() = listDao.getListsFlow()

    fun listItems(listId: Long): Flow<List<UserListItem>> = listDao.getItemsFlow(listId)
    fun notesFor(tmdbId: Int, mediaType: String): Flow<List<TitleNote>> =
        listDao.getNotesFlow(tmdbId, mediaType)

    suspend fun isWatchlisted(key: String): Boolean = watchlistDao.getByKey(key) != null
    suspend fun upsertWatchlist(value: WatchlistEntry) = watchlistDao.upsert(value)
    suspend fun removeWatchlist(key: String) = watchlistDao.deleteByKey(key)

    suspend fun savePosition(value: ContinueWatchingEntry) = continueDao.upsert(value)
    suspend fun positionFor(key: String): Long = continueDao.getPosition(key) ?: 0L
    suspend fun clearPosition(key: String) = continueDao.deleteByKey(key)

    suspend fun upsertList(value: UserList): Long = listDao.upsertList(value)
    suspend fun upsertListItem(value: UserListItem): Long = listDao.upsertItem(value)
    suspend fun deleteList(value: UserList) = listDao.deleteList(value)
    suspend fun deleteListItem(value: UserListItem) = listDao.deleteItem(value)

    suspend fun upsertNote(value: TitleNote): Long = listDao.upsertNote(value)
    suspend fun deleteNote(value: TitleNote) = listDao.deleteNote(value)

    val addons: Flow<List<AddonEntity>> get() = addonDao.observeAllOrdered()
    val enabledAddons: Flow<List<AddonEntity>> get() = addonDao.observeEnabledOrdered()

    suspend fun upsertAddon(value: AddonEntity): Long = addonDao.upsert(value)
    suspend fun addonById(dbId: Long): AddonEntity? = addonDao.getById(dbId)
    suspend fun addonByManifestUrl(manifestUrl: String): AddonEntity? =
        addonDao.getByManifestUrl(manifestUrl)
    suspend fun addonCount(): Int = addonDao.count()
    suspend fun deleteAddon(dbId: Long) = addonDao.deleteById(dbId)
    suspend fun setAddonEnabled(dbId: Long, enabled: Boolean) = addonDao.setEnabled(dbId, enabled)
    suspend fun swapAddonPriorities(first: AddonEntity, second: AddonEntity) =
        addonDao.swapPriorities(first, second)
    suspend fun setAddonError(dbId: Long, error: String?) = addonDao.setError(dbId, error)
    suspend fun setAddonConfig(dbId: Long, config: String) = addonDao.setConfig(dbId, config)

    companion object {
        @Volatile
        private var instance: UniflixRepository? = null

        fun get(context: Context): UniflixRepository =
            instance ?: synchronized(this) {
                instance ?: UniflixRepository(context).also { instance = it }
            }

        /** Test seam: drop the singleton so the next [get] re-resolves its database. */
        internal fun resetInstanceForTest() {
            synchronized(this) { instance = null }
        }
    }
}

/** Stable join key for a title (optionally a specific episode). */
fun watchKey(tmdbId: Int, mediaType: String, season: Int = 0, episode: Int = 0): String =
    "$mediaType:$tmdbId:$season:$episode"
