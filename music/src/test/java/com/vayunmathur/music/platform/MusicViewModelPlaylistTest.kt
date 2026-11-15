package com.vayunmathur.music.platform

import android.app.Application
import android.content.ComponentName
import android.os.Binder
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.music.FakeAndroidKeyStore
import com.vayunmathur.music.FakeMediaProvider
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.Playlist
import com.vayunmathur.music.data.TestMusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [MusicViewModel] playlist CRUD against a real in-memory Room database
 * (see TestMusicRepository): create/rename/add/remove/delete must land in
 * the repository, and the matching lookups the detail screens use must
 * reflect them. ViewModel writes launch on the main dispatcher, so tests
 * pump Robolectric's main looper while polling the repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MusicViewModelPlaylistTest {

    companion object {
        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            // The fake provider + repository singleton outlive this class in
            // Robolectric's shared sandbox: switch the provider off and
            // republish an empty library for later classes.
            FakeMediaProvider.enabled = false
            val app = ApplicationProvider.getApplicationContext<Application>()
            runBlocking { MusicRepository.get(app).refresh() }
        }
    }


    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var repo: MusicRepository

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        // refreshLibrary launches on viewModelScope (Main); Robolectric's
        // paused main looper does not drain it reliably, so use an immediate
        // Main dispatcher (the IO playlist writes are unaffected).
        Dispatchers.setMain(Dispatchers.Unconfined)
        FakeMediaProvider.enabled = true
        FakeAndroidKeyStore.install()
        ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        Shadows.shadowOf(app).setComponentNameAndServiceForBindService(
            ComponentName(app, com.vayunmathur.music.service.PlaybackService::class.java),
            Binder(),
        )
        val (repository, _) = TestMusicRepository.install(app)
        repo = repository
        runBlocking { repo.refresh() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): MusicViewModel =
        MusicViewModel(app, repo, PlaybackManager.getInstance(app))

    private fun await(description: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (runBlocking { condition() }) return
            Thread.sleep(25)
        }
        assertTrue(runBlocking { condition() }, "timed out waiting for: $description")
    }

    @Test
    fun createRenameAddRemoveAndDeletePlaylistThroughTheViewModel() {
        val vm = viewModel()
        var createdId = -1L
        vm.createPlaylist("Gym") { createdId = it }
        await("playlist created") { repo.getAllPlaylists().any { it.name == "Gym" } }
        val playlist = runBlocking { repo.getAllPlaylists().first { it.name == "Gym" } }
        assertEquals(playlist.id, createdId)

        vm.renamePlaylist(playlist, "Gym Mix")
        await("playlist renamed") {
            repo.getAllPlaylists().firstOrNull { it.id == playlist.id }?.name == "Gym Mix"
        }

        vm.addMusicToPlaylist(playlist.id, musicId = 1L)
        vm.addMusicToPlaylist(playlist.id, musicId = 3L)
        await("tracks added") {
            repo.getFromRight(playlist.id, TYPE_MUSIC_PLAYLIST).toSet() == setOf(1L, 3L)
        }

        vm.removeMusicFromPlaylist(playlist.id, musicId = 1L)
        await("track removed") {
            repo.getFromRight(playlist.id, TYPE_MUSIC_PLAYLIST) == listOf(3L)
        }

        var done = false
        vm.deletePlaylist(Playlist(id = playlist.id, name = "Gym Mix")) { done = true }
        await("playlist deleted") { repo.getAllPlaylists().isEmpty() }
        assertTrue(done, "delete completion callback must run")
    }

    @Test
    fun refreshLibraryPublishesTheFakeLibraryThroughTheViewModel() {
        val vm = viewModel()
        // The library StateFlows are WhileSubscribed: collect them or the
        // upstream MediaStore read never starts.
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val jobs = listOf(
            scope.launch { vm.music.collect {} },
            scope.launch { vm.albums.collect {} },
            scope.launch { vm.artists.collect {} },
            scope.launch { vm.loaded.collect {} },
        )
        vm.refreshLibrary()
        // refresh() is synchronous MediaStore work on viewModelScope; the
        // Room backfill behind it uses the in-memory database in tests.
        await("library loaded") { vm.music.value.size == 3 && vm.loaded.value }
        assertEquals(listOf("Album One", "Album Two"), vm.albums.value.map { it.name })
        assertEquals("Artist One", vm.artists.value.first { it.id == 11L }.name)
        jobs.forEach { it.cancel() }
    }
}
