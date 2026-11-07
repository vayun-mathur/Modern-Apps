package com.vayunmathur.music.platform

import android.app.Application
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ViewModel playlist actions on device, where the SQLCipher database can
 * actually open (its native library cannot load under Robolectric, and
 * firing these writes there would also leak uncaught IO exceptions into
 * unrelated tests sharing the JVM).
 */
class MusicViewModelDeviceTest {

    private val app: Application
        get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, MusicRepository.get(app), PlaybackManager.getInstance(app))

    @Test
    fun playlistActionsRoundTrip() {
        val repo = MusicRepository.get(app)
        val vm = viewModel()

        val created = CountDownLatch(1)
        var createdId = -1L
        vm.createPlaylist("Device Mix") { id -> createdId = id; created.countDown() }
        assertTrue(created.await(15, TimeUnit.SECONDS))
        assertTrue(createdId > 0)

        runBlocking {
            assertTrue(repo.getAllPlaylists().any { it.id == createdId && it.name == "Device Mix" })
            // No members yet: the DAO read itself is what must succeed.
            assertEquals(emptyList<Long>(), vm.getMusicInPlaylist(createdId))
        }

        vm.addMusicToPlaylist(createdId, 4242L)
        // addMusicToPlaylist has an onDone callback in its full form via the
        // repository; poll the DAO read until the write lands.
        val deadline = System.currentTimeMillis() + 15_000
        var members: List<Long> = emptyList()
        while (System.currentTimeMillis() < deadline) {
            members = runBlocking { repo.getFromRight(createdId, TYPE_MUSIC_PLAYLIST) }
            if (members == listOf(4242L)) break
            Thread.sleep(100)
        }
        assertEquals(listOf(4242L), members)

        vm.removeMusicFromPlaylist(createdId, 4242L)
        vm.renamePlaylist(Playlist(id = createdId, name = "Device Mix"), "Device Mix Renamed")
        val done = CountDownLatch(1)
        vm.deletePlaylist(Playlist(id = createdId, name = "Device Mix Renamed")) { done.countDown() }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        runBlocking {
            assertTrue(repo.getAllPlaylists().none { it.id == createdId })
        }
    }
}
