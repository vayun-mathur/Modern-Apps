package com.vayunmathur.music.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.music.FakeAndroidKeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Repository against the real (SQLCipher) database and Robolectric's empty
 * MediaStore: the library side publishes empty lists, the Room side does
 * full playlist/matching CRUD.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicRepositoryTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun repo(): MusicRepository = MusicRepository.get(context)

    @Test
    fun `singleton returns the same instance`() {
        assertSame(repo(), repo())
    }

    @Test
    fun `refresh publishes an empty library without media`() = runBlocking {
        val repo = repo()
        repo.refresh()
        assertEquals(emptyList(), repo.getAllMusic())
        assertEquals(emptyList(), repo.getAllAlbums())
        assertEquals(emptyList(), repo.getAllArtists())
        assertTrue(repo.loaded.first())
        assertEquals(emptyList(), repo.music.first())
    }
}
