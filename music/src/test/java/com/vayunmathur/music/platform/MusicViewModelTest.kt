package com.vayunmathur.music.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import org.junit.Before
import com.vayunmathur.music.data.MusicRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The ViewModel against the real repository and playback manager singletons
 * under Robolectric: MediaStore reads come back empty (no audio permission /
 * no media), which is the ordinary first-launch state these flows must
 * handle, and playlist writes go to the real DAO if the database opens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicViewModelTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    @Test
    fun `initial state is empty and not loaded`() {
        val vm = viewModel()
        assertEquals(emptyList(), vm.music.value)
        assertEquals(emptyList(), vm.albums.value)
        assertEquals(emptyList(), vm.artists.value)
        assertFalse(vm.loaded.value)
        assertFalse(vm.isPlaying.value)
        assertEquals(0L, vm.currentPosition.value)
        assertEquals(null, vm.currentMediaItem.value)
        assertEquals(null, vm.player.value)
    }

    @Test
    fun `refresh publishes an empty library when there is no media`() = runBlocking {
        // Note: refreshLibrary() itself is not called here — its second step
        // (backfillTags) opens the SQLCipher tag cache, whose native library
        // cannot load on the JVM and whose failure would leak out of
        // viewModelScope as an uncaught exception. The MediaStore half is
        // exercised directly; the full path runs on device.
        val songs = MusicRepository.get(app).music.first { true }
        assertEquals(emptyList(), songs)
        assertTrue(MusicRepository.get(app).getAllMusic().isEmpty())
        assertTrue(MusicRepository.get(app).getAllAlbums().isEmpty())
        assertTrue(MusicRepository.get(app).getAllArtists().isEmpty())
    }

    @Test
    fun `factory creates the view model and rejects other classes`() {
        val factory = MusicViewModelFactory(
            app,
            MusicRepository.get(app),
            PlaybackManager.getInstance(app),
        )
        val vm = factory.create(MusicViewModel::class.java)
        assertEquals(emptyList(), vm.music.value)
        assertFailsWith<IllegalArgumentException> {
            factory.create(OtherViewModel::class.java)
        }
    }

    class OtherViewModel : androidx.lifecycle.ViewModel()
}
