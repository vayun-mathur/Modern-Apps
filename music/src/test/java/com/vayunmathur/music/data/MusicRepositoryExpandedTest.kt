package com.vayunmathur.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MusicRepository's in-memory (MediaStore) half against the populated fake
 * library: refresh publishing, idempotence, and the loaded flag. The Room half
 * (playlists, matchings, tag cache) needs SQLCipher, which cannot load on a host
 * JVM, so it is not asserted here; backfillTags is likewise device-only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositoryExpandedTest {

    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Context>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }

    @Before
    fun setUp() {
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun repo(): MusicRepository = MusicRepository.get(context)

    @Test
    fun `refresh publishes songs albums artists and the loaded flag`() = runBlocking {
        val repo = repo()
        repo.refresh()
        assertEquals(listOf(1L, 2L, 3L), repo.getAllMusic().map { it.id })
        assertEquals(listOf(21L, 22L), repo.getAllAlbums().map { it.id })
        assertEquals(listOf(11L, 12L), repo.getAllArtists().map { it.id })
        assertTrue(repo.loaded.first())
        assertEquals(repo.getAllMusic(), repo.music.first())
        assertEquals(repo.getAllAlbums(), repo.albums.first())
        assertEquals(repo.getAllArtists(), repo.artists.first())
    }

    @Test
    fun `refresh is repeatable and reflects the provider being switched off`() = runBlocking {
        val repo = repo()
        repo.refresh()
        repo.refresh()
        assertEquals(3, repo.getAllMusic().size)

        FakeMediaProvider.enabled = false
        repo.refresh()
        assertEquals(emptyList(), repo.getAllMusic())
        assertEquals(emptyList(), repo.getAllAlbums())
        assertEquals(emptyList(), repo.getAllArtists())
        // Once loaded, the flag stays up even for an empty library: "loaded, and
        // there is nothing" is a different screen from "not read yet".
        assertTrue(repo.loaded.first())
    }

    @Test
    fun `refreshed songs keep disc track duration and year split from MediaStore`() = runBlocking {
        val repo = repo()
        repo.refresh()
        val songs = repo.getAllMusic().associateBy { it.id }
        assertEquals(1, songs.getValue(1L).discNumber)
        assertEquals(2, songs.getValue(1L).trackNumber)
        assertEquals(200_000L, songs.getValue(1L).duration)
        assertEquals(2019, songs.getValue(1L).year)
        assertEquals(2, songs.getValue(2L).discNumber)
        assertEquals(5, songs.getValue(2L).trackNumber)
        assertEquals(0, songs.getValue(2L).year)
        assertEquals("Album Two", songs.getValue(3L).album)
        assertEquals(22L, songs.getValue(3L).albumId)
    }
}
