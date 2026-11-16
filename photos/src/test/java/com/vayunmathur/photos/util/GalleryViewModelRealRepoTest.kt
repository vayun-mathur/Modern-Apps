package com.vayunmathur.photos.util

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.FaceRepository
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.awaitPhotosCondition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GalleryViewModel] over the real repositories (in-memory Room via
 * [TestPhotosRepository]): the default constructor's store adapters, the
 * people/albums/favorites projections, search, trash/delete and person
 * naming all run production code against genuine rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryViewModelRealRepoTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        // viewModelScope is Main-bound; under Robolectric the Android Main
        // dispatcher is a paused looper the runBlocking test thread cannot
        // drain, so substitute an unconfined Main for the VM's lifetime.
        Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false, favorite: Boolean = false) = Photo(
        name = name, uri = "content://media/$name", date = date, width = 100, height = 100,
        dateModified = date, exifSet = true, lat = null, long = null,
        videoData = null, panoData = null, isTrashed = trashed, album = album, isFavorite = favorite,
    )

    private fun viewModel(): GalleryViewModel =
        GalleryViewModel(app).also { it.runSyncHook = {} }

    private fun pump() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    private suspend fun await(desc: String, cond: suspend () -> Boolean) {
        val ok = awaitPhotosCondition(timeoutMs = 30_000) { pump(); cond() }
        assertTrue(ok, "timed out waiting for: $desc")
    }

    private fun collect(vm: GalleryViewModel) = listOf(
        CoroutineScope(Dispatchers.Default).launch { vm.indexTargetCount.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.ocrCount.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.clipCount.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.faceScannedCount.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.searchResults.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.photos.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.albums.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.people.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.favorites.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.faceCountByPhoto.collect {} },
        CoroutineScope(Dispatchers.Default).launch { vm.faceBoxesByPhoto.collect {} },
    )

    @Test fun galleryProjectionsReflectSeededRows() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(
            photo("beach", 300L, album = "Camera", favorite = true),
            photo("mountain", 200L, album = "Camera"),
            photo("city", 100L, album = "Trips"),
            photo("trashed-one", 400L, album = "Camera", trashed = true),
        ))
        val vm = viewModel()
        val jobs = collect(vm)
        // The photos flow is the raw library order (newest first, trash included);
        // trash/album filtering happens in the grouping projections below.
        await("photos loaded") { vm.photos.value.map { it.name } == listOf("trashed-one", "beach", "mountain", "city") }
        await("albums grouped") { vm.albums.value.map { it.name } == listOf("Camera", "Trips") }
        val camera = vm.albums.value.first { it.name == "Camera" }
        assertEquals("beach", camera.coverPhoto.name)
        assertEquals(2, camera.photos.size)
        await("favorites projected") { vm.favorites.value.map { it.name } == listOf("beach") }
        vm.setAlbumCover("Camera", vm.photos.value.first { it.name == "mountain" }.id)
        await("cover overridden") {
            vm.albums.value.first { it.name == "Camera" }.coverPhoto.name == "mountain"
        }
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun searchFindsOcrAndNameMatches() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(photo("beach sunset", 300L), photo("mountain", 200L)))
        val id = repo.getAll().first { it.name == "mountain" }.id
        installed.scan.setOcrResults(listOf(com.vayunmathur.photos.data.OcrResult(id, "welcome sign", null)))
        val vm = viewModel()
        val jobs = collect(vm)
        vm.setSearchQuery("beach")
        await("name search") { vm.searchResults.value.map { it.name } == listOf("beach sunset") }
        vm.setSearchQuery("welcome")
        await("ocr search") { vm.searchResults.value.map { it.name } == listOf("mountain") }
        vm.setSearchQuery("zzz-no-match")
        await("empty search") { vm.searchResults.value.isEmpty() }
        vm.setSearchQuery("")
        assertEquals("", vm.searchQuery.value)
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun trashDeleteAndSelectionMutateRoom() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(photo("a", 200L), photo("b", 100L)))
        val vm = viewModel()
        val jobs = collect(vm)
        await("loaded") { vm.photos.value.size == 2 }
        val a = vm.photos.value.first { it.name == "a" }
        vm.toggleSelection(a.id)
        assertEquals(setOf(a.id), vm.selectedIds.value)
        vm.trashPhotoLocally(a)
        await("trashed in room") { repo.getAll().first { it.id == a.id }.isTrashed }
        await("trashed flag projected") { vm.photos.value.first { it.name == "a" }.isTrashed }
        val b = repo.getAll().first { it.name == "b" }
        vm.deletePhoto(b)
        await("deleted from room") { repo.getAll().map { it.name } == listOf("a") }
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun peopleProjectionGroupsFacesAndNamesPerson() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(photo("group", 300L), photo("solo", 200L)))
        val (group, solo) = repo.getAll().sortedBy { it.name }.let { listOf(it[0], it[1]) }
        // Two clusterable faces on one photo + one on the other, all one person-sized cluster.
        val faces = listOf(
            PhotoFace(photoId = group.id, clusterId = null, embedding = FaceRecognizer.floatsToBytes(FloatArray(4) { 1f }),
                clusterable = true, left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 100),
            PhotoFace(photoId = group.id, clusterId = null, embedding = FaceRecognizer.floatsToBytes(FloatArray(4) { 0.9f }),
                clusterable = true, left = 0.5f, top = 0f, right = 1f, bottom = 0.5f, srcWidth = 100, srcHeight = 100),
            PhotoFace(photoId = solo.id, clusterId = null, embedding = FaceRecognizer.floatsToBytes(FloatArray(4) { 0.8f }),
                clusterable = true, left = 0f, top = 0f, right = 0.4f, bottom = 0.4f, srcWidth = 100, srcHeight = 100),
        )
        installed.faces.commitFaceScan(listOf(group.id, solo.id), faces)
        val rows = installed.faces.getClusterRows()
        installed.faces.applyClustering(
            listOf(com.vayunmathur.photos.data.ResolvedPersonRow(null, rows.first().embedding, rows.map { it.id })),
            emptyList(),
        )
        val vm = viewModel()
        val jobs = collect(vm)
        await("person projected") { vm.people.value.size == 1 }
        val person = vm.people.value.single()
        assertEquals(2, person.photos.size)
        assertEquals(mapOf(group.id to 2, solo.id to 1), vm.faceCountByPhoto.value)
        assertTrue(vm.faceBoxesByPhoto.value[group.id]?.faces?.size == 2)
        vm.setPersonName(person.id, "Alice")
        await("name persisted") { installed.faces.getPersons().single().name == "Alice" }
        await("cluster photos resolved") { vm.photosForCluster(person.id).size == 2 }
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun pendingAlbumMoveBookkeeping() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(photo("a", 100L)))
        val vm = viewModel()
        assertFalse(vm.consumePendingMove())
        val row = repo.getAll().single()
        vm.requestAlbumMove(listOf(row), "Camera")
        // Consuming triggers the MediaStore move path; with no grantable
        // provider under Robolectric it must not throw and must consume.
        assertTrue(vm.consumePendingMove())
        assertFalse(vm.consumePendingMove())
        // Empty moves are ignored entirely.
        vm.moveToAlbum(emptyList(), "Camera")
        Unit
    }

    private class UpdateProvider : android.content.ContentProvider() {
        override fun onCreate() = true
        override fun query(
            uri: android.net.Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): android.database.Cursor = android.database.MatrixCursor(projection ?: arrayOf("_id"))
        override fun getType(uri: android.net.Uri): String? = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?): android.net.Uri? = null
        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: android.net.Uri, values: android.content.ContentValues?, selection: String?,
            selectionArgs: Array<out String>?,
        ) = 1
    }

    @Test fun albumMoveAppliesThroughMediaStoreAndRoom() = runBlocking {
        val provider = UpdateProvider()
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply {
            authority = "media"
            packageName = app.packageName
        })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        val repo = installed.photos
        repo.upsertAll(listOf(photo("mover", 100L)))
        val seeded = repo.getAll().first()
        val vm = viewModel()
        vm.moveToAlbum(listOf(seeded), "Holidays")
        await("album moved in room") { repo.getAll().first().album == "Holidays" }
        vm.requestAlbumMove(listOf(seeded), null)
        assertTrue(vm.consumePendingMove())
        await("album cleared in room") { repo.getAll().first().album == null }
        assertFalse(vm.consumePendingMove())
        vm.moveToAlbum(emptyList(), "Nowhere")
        Unit
    }

    @Test fun resolveAndIndexFindsSeededRow() = runBlocking {
        val repo = installed.photos
        repo.upsertAll(listOf(photo("indexed", 100L)))
        val seeded = repo.getAll().first()
        val vm = viewModel()
        var resolved: Long? = -1L
        vm.resolveAndIndex(android.net.Uri.parse(seeded.uri)) { resolved = it }
        await("resolved") { resolved == seeded.id }
        Unit
    }


    @Test fun syncHooksAndFactoryRun() = runBlocking {
        val vm = viewModel()
        var syncs = 0
        vm.runSyncHook = { syncs++ }
        vm.runSync()
        assertEquals(1, syncs)
        val factory = GalleryViewModelFactory(app, installed.photos)
        assertTrue(factory.create(GalleryViewModel::class.java, androidx.lifecycle.viewmodel.MutableCreationExtras()) is GalleryViewModel)
        // Progress counters reflect the real scan state.
        installed.photos.upsertAll(listOf(photo("a", 1L)))
        val jobs = collect(vm)
        await("index target") { vm.indexTargetCount.value == 1 }
        assertEquals(0, vm.ocrCount.value)
        assertEquals(0, vm.clipCount.value)
        assertEquals(0, vm.faceScannedCount.value)
        assertFalse(vm.isRefreshing.value)
        assertFalse(vm.faceIndexing.value)
        jobs.forEach { it.cancel() }
        Unit
    }

    @Test fun repositorySingletonsAreTheOnesViewModelUses() = runBlocking {
        assertEquals(installed.photos, PhotosRepository.get(app))
        assertEquals(installed.faces, FaceRepository.get(app))
        installed.photos.upsertAll(listOf(
            photo("video", 10L).copy(videoData = VideoData(1_000L)),
        ))
        assertEquals(0, installed.photos.getIndexTargetCountFlow().first())
    }
}
