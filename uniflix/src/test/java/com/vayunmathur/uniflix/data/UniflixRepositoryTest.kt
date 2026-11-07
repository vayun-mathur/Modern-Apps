package com.vayunmathur.uniflix.data

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * DAO CRUD over an in-memory [UniflixDatabase] under Robolectric.
 * (UniflixRepository itself wraps the DB in SQLCipher via AndroidKeyStore,
 * which Robolectric does not provide, so it cannot be opened here.)
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class UniflixRepositoryTest {

    /** Thin DAO facade mirroring the repository calls under test. */
    private class Repo(private val db: UniflixDatabase) {
        val watchlist get() = db.watchlistDao().getAllFlow()
        val continueWatching get() = db.continueWatchingDao().getAllFlow()
        val userLists get() = db.userListDao().getListsFlow()
        fun listItems(listId: Long) = db.userListDao().getItemsFlow(listId)
        fun notesFor(tmdbId: Int, mediaType: String) = db.userListDao().getNotesFlow(tmdbId, mediaType)
        suspend fun isWatchlisted(key: String) = db.watchlistDao().getByKey(key) != null
        suspend fun upsertWatchlist(v: WatchlistEntry) = db.watchlistDao().upsert(v)
        suspend fun removeWatchlist(key: String) = db.watchlistDao().deleteByKey(key)
        suspend fun savePosition(v: ContinueWatchingEntry) = db.continueWatchingDao().upsert(v)
        suspend fun positionFor(key: String) = db.continueWatchingDao().getPosition(key) ?: 0L
        suspend fun clearPosition(key: String) = db.continueWatchingDao().deleteByKey(key)
        suspend fun upsertList(v: UserList) = db.userListDao().upsertList(v)
        suspend fun upsertListItem(v: UserListItem) = db.userListDao().upsertItem(v)
        suspend fun deleteList(v: UserList) = db.userListDao().deleteList(v)
        suspend fun deleteListItem(v: UserListItem) = db.userListDao().deleteItem(v)
        suspend fun upsertNote(v: TitleNote) = db.userListDao().upsertNote(v)
        suspend fun deleteNote(v: TitleNote) = db.userListDao().deleteNote(v)
        val addons get() = db.addonDao().observeAllOrdered()
        val enabledAddons get() = db.addonDao().observeEnabledOrdered()
        suspend fun upsertAddon(v: AddonEntity) = db.addonDao().upsert(v)
        suspend fun addonById(id: Long) = db.addonDao().getById(id)
        suspend fun addonByManifestUrl(u: String) = db.addonDao().getByManifestUrl(u)
        suspend fun addonCount() = db.addonDao().count()
        suspend fun deleteAddon(id: Long) = db.addonDao().deleteById(id)
        suspend fun setAddonEnabled(id: Long, e: Boolean) = db.addonDao().setEnabled(id, e)
        suspend fun swapAddonPriorities(a: AddonEntity, b: AddonEntity) = db.addonDao().swapPriorities(a, b)
        suspend fun setAddonError(id: Long, e: String?) = db.addonDao().setError(id, e)
        suspend fun setAddonConfig(id: Long, c: String) = db.addonDao().setConfig(id, c)
    }

    private fun repo(): Repo {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room3.Room.inMemoryDatabaseBuilder(ctx, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        return Repo(db)
    }

    @Test fun watchKeyFormat() {
        assertEquals("movie:42:0:0", watchKey(42, "movie"))
        assertEquals("tv:7:2:5", watchKey(7, "tv", season = 2, episode = 5))
    }

    @Test fun watchlistAndContinueWatchingCrud() = runTest {
        val repo = repo()
        val key = watchKey(901, "movie")
        repo.removeWatchlist(key)
        assertFalse(repo.isWatchlisted(key))
        repo.upsertWatchlist(
            WatchlistEntry(key, 901, "movie", "Film", "/p.jpg", addedAt = 10L))
        assertTrue(repo.isWatchlisted(key))
        assertEquals(listOf("Film"), repo.watchlist.first().filter { it.key == key }.map { it.title })
        repo.removeWatchlist(key)
        assertFalse(repo.isWatchlisted(key))

        val posKey = watchKey(902, "tv", 1, 3)
        repo.clearPosition(posKey)
        assertEquals(0L, repo.positionFor(posKey))
        repo.savePosition(
            ContinueWatchingEntry(
                posKey, 902, "tv", "Show", null,
                season = 1, episode = 3, positionMs = 55_000L, durationMs = 90_000L))
        assertEquals(55_000L, repo.positionFor(posKey))
        assertTrue(repo.continueWatching.first().any { it.key == posKey && it.episode == 3 })
        repo.clearPosition(posKey)
        assertEquals(0L, repo.positionFor(posKey))
    }

    @Test fun listsItemsAndNotesCrud() = runTest {
        val repo = repo()
        val listId = repo.upsertList(UserList(name = "Favourites", createdAt = 1L))
        assertTrue(listId > 0)
        val itemId = repo.upsertListItem(
            UserListItem(listId = listId, tmdbId = 903, mediaType = "movie", title = "Listed", posterPath = null))
        assertTrue(itemId > 0)
        assertEquals(listOf("Listed"), repo.listItems(listId).first().map { it.title })
        assertTrue(repo.userLists.first().any { it.id == listId && it.name == "Favourites" })

        val noteId = repo.upsertNote(
            TitleNote(tmdbId = 903, mediaType = "movie", body = "great", score = 5))
        assertTrue(noteId > 0)
        val notes = repo.notesFor(903, "movie").first()
        assertEquals(listOf("great"), notes.filter { it.id == noteId }.map { it.body })

        repo.deleteNote(TitleNote(id = noteId, tmdbId = 903, mediaType = "movie", body = "great", score = 5))
        assertTrue(repo.notesFor(903, "movie").first().none { it.id == noteId })
        repo.deleteListItem(
            UserListItem(id = itemId, listId = listId, tmdbId = 903, mediaType = "movie", title = "Listed", posterPath = null))
        assertTrue(repo.listItems(listId).first().isEmpty())
        repo.deleteList(UserList(id = listId, name = "Favourites", createdAt = 1L))
        assertTrue(repo.userLists.first().none { it.id == listId })
    }

    @Test fun addonCrudOrderingAndFlags() = runTest {
        val repo = repo()
        // Clean slate for the shared local database.
        for (addon in repo.addons.first()) repo.deleteAddon(addon.dbId)
        assertEquals(0, repo.addonCount())
        val idA = repo.upsertAddon(
            AddonEntity(
                manifestUrl = "https://a.example/manifest.json", baseUrl = "https://a.example",
                addonId = "a", name = "Alpha", priority = 5))
        val idB = repo.upsertAddon(
            AddonEntity(
                manifestUrl = "https://b.example/manifest.json", baseUrl = "https://b.example",
                addonId = "b", name = "Beta", priority = 1))
        assertEquals(2, repo.addonCount())
        // Ordered by priority, not insertion order.
        assertEquals(listOf("Beta", "Alpha"), repo.addons.first().map { it.name })
        assertEquals("Alpha", repo.addonById(idA)?.name)
        assertEquals("b", repo.addonByManifestUrl("https://b.example/manifest.json")?.addonId)
        assertNull(repo.addonById(999_999L))

        repo.setAddonEnabled(idB, false)
        assertEquals(listOf("Alpha"), repo.enabledAddons.first().map { it.name })
        repo.setAddonError(idA, "boom")
        assertEquals("boom", repo.addonById(idA)?.lastError)
        repo.setAddonError(idA, null)
        assertNull(repo.addonById(idA)?.lastError)
        repo.setAddonConfig(idA, """{"k":1}""")
        assertEquals("""{"k":1}""", repo.addonById(idA)?.configJson)

        val alpha = repo.addonById(idA)!!
        val beta = repo.addonById(idB)!!
        repo.swapAddonPriorities(alpha, beta)
        assertEquals(1, repo.addonById(idA)?.priority)
        assertEquals(5, repo.addonById(idB)?.priority)

        repo.deleteAddon(idA)
        repo.deleteAddon(idB)
        assertEquals(0, repo.addonCount())
        assertNotNull(repo)
    }
}
