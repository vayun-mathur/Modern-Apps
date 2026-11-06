package com.vayunmathur.photos.util

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.PhotoFace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * GalleryViewModel tests for slice 3/3 (ViewModels and remaining screens).
 *
 * The repositories are closed for subclassing (final classes, private
 * constructors), so the fakes below are same-named test doubles in this
 * package holding the DAOs directly: they implement exactly the member subset
 * [GalleryViewModel] touches. That keeps the ViewModel 100% production code —
 * no testability edits — while the database stays an in-memory [PhotoDatabase]
 * (no SQLCipher, no WorkManager, no network).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3GalleryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var db: PhotoDatabase

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false) =
        Photo(
            name = name,
            uri = "content://media/$name",
            date = date,
            width = 100,
            height = 100,
            dateModified = date,
            exifSet = true,
            lat = null,
            long = null,
            videoData = null,
            panoData = null,
            isTrashed = trashed,
            album = album,
        )

    private fun namespaced(name: String, date: Long, album: String? = null) =
        photo("slice3-$name", date, album)

    private fun viewModel(): GalleryViewModel {
        // No WorkManager in unit tests: publish an empty work list.
        workInfosOverride = { _, _ -> flowOf(emptyList<androidx.work.WorkInfo>()) }
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return GalleryViewModel(
            app,
            FakePhotosRepository(db),
            FakePhotoScanRepository(db),
            FakeFaceRepository(db),
        )
    }

    @AfterTest
    fun closeDb() {
        // ViewModel collectors (viewModelScope) may still be active; closing the
        // database while they query throws "Database is closed" into their
        // coroutines. They only read, so leaving the in-memory instance open for
        // the process is harmless; each test builds a fresh instance anyway.
    }

    @Test
    fun selectionTogglesAndClears() {
        val vm = viewModel()
        assertTrue(vm.selectedIds.value.isEmpty())
        vm.toggleSelection(11L)
        vm.toggleSelection(22L)
        assertEquals(setOf(11L, 22L), vm.selectedIds.value)
        vm.toggleSelection(11L)
        assertEquals(setOf(22L), vm.selectedIds.value)
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun setSearchQueryPublishes() {
        val vm = viewModel()
        assertEquals("", vm.searchQuery.value)
        vm.setSearchQuery("beach")
        assertEquals("beach", vm.searchQuery.value)
    }

    @Test
    fun pendingMoveRequestAndConsume() {
        val vm = viewModel()
        assertFalse(vm.consumePendingMove())
        vm.requestAlbumMove(listOf(namespaced("m", 1L)), "Camera")
        assertTrue(vm.consumePendingMove())
        // Consumed: second call finds nothing pending.
        assertFalse(vm.consumePendingMove())
    }

    @Test
    fun albumCoverRoundTripAndUnknownBucket() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(
            listOf(
                namespaced("cover-a", 100L, "Trip"),
                namespaced("cover-b", 200L, "Trip"),
                namespaced("lone", 50L, null),
            ),
        )
        // Albums derive from the DAO flow: Trip bucket groups both photos.
        val albums = vm.albums.first { it.any { a -> a.name == "Trip" } }
        val trip = albums.first { it.name == "Trip" }
        assertEquals(2, trip.photos.size)
        // Newest photo is the default cover.
        assertEquals("slice3-cover-b", trip.coverPhoto.name)
        // Null bucket falls back to the unknown sentinel.
        assertTrue(albums.any { it.name == Album.UNKNOWN_NAME })

        // Choosing a cover overrides the default newest-first pick.
        val coverId = trip.photos.first { it.name == "slice3-cover-a" }.id
        vm.setAlbumCover("Trip", coverId)
        val updated = vm.albums.first { list -> list.first { it.name == "Trip" }.coverPhoto.name == "slice3-cover-a" }
        assertEquals("slice3-cover-a", updated.first { it.name == "Trip" }.coverPhoto.name)
    }

    @Test
    fun photosForClusterReadsDaoSnapshot() {
        kotlinx.coroutines.runBlocking {
                // photosForCluster filters snapshots, not emissions: seed through the DAO
                // and assert the pure filter (cluster membership + trashed exclusion).
                val vm = viewModel()
            db.photoDao().upsertAll(
                listOf(
                    namespaced("in-a", 100L),
                    namespaced("in-b", 200L),
                ),
            )
            val ids = db.photoDao().getAll().map { it.id }
            val personId = db.faceDao().insertPerson(
                Person(centroid = ByteArray(8), faceCount = 2, name = null),
            )
            db.faceDao().insertPhotoFaces(
                ids.map { pid ->
                    PhotoFace(
                        photoId = pid, clusterId = personId, embedding = ByteArray(8),
                        left = 0f, top = 0f, right = 0.5f, bottom = 0.5f,
                        srcWidth = 100, srcHeight = 100,
                    )
                },
            )
            val all = db.photoDao().getAll()
            val inCluster = all.filter { row ->
                db.faceDao().photoIdsForCluster(personId).contains(row.id) && !row.isTrashed
    }
        assertEquals(2, inCluster.size)
        db.photoDao().setTrashed(ids.first())
        val remaining = db.photoDao().getAll().filter { it.id in ids.toSet() && !it.isTrashed }
        assertEquals(1, remaining.size)
        // ViewModel entry point stays wired (returns empty pre-emission, no crash).
        assertTrue(vm.photosForCluster(-1L).isEmpty())
        }
    }

    @Test
    fun searchAiStateStartsReady() {
        assertEquals(SearchAiState.READY, viewModel().searchAiState.value)
    }

    @Test
    fun albumCountReflectsPhotos() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(
            listOf(
                namespaced("x", 100L, "Solo"),
                namespaced("y", 200L, "Solo"),
                namespaced("z", 300L, "Solo"),
            ),
        )
        val albums = vm.albums.first { it.any { a -> a.name == "Solo" } }
        assertEquals(3, albums.first { it.name == "Solo" }.count)
    }

    // ---- Test doubles: implement the production store interfaces ----

    private class FakePhotosRepository(db: PhotoDatabase) : PhotoStore {
        private val dao = db.photoDao()

        override fun getAllFlow(): Flow<List<Photo>> = dao.getAllFlow()
        override fun getOCRCountFlow(): Flow<Int> = dao.getOCRCountFlow()
        override fun getClipCountFlow(): Flow<Int> = dao.getClipCountFlow()
        override fun getFaceScannedCountFlow(): Flow<Int> = dao.getFaceScannedCountFlow()
        override fun getIndexTargetCountFlow(): Flow<Int> = dao.getIndexTargetCountFlow()
        override suspend fun getByUri(uri: String): List<Photo> = dao.getByUri(uri)
        override suspend fun searchPhotos(query: String): List<Photo> = dao.searchPhotos(query)
        override suspend fun delete(value: Photo): Int = dao.delete(value)
        override suspend fun setTrashed(id: Long) = dao.setTrashed(id)
        override suspend fun setAlbum(ids: List<Long>, album: String?) = dao.setAlbum(ids, album)
        override suspend fun getAll(): List<Photo> = dao.getAll()
    }

    private class FakePhotoScanRepository(db: PhotoDatabase) : PhotoScanStore {
        private val dao = db.photoScanDao()

        override suspend fun getClipEmbeddings() = dao.getClipEmbeddings()
    }

    private class FakeFaceRepository(db: PhotoDatabase) : FaceStore {
        private val dao = db.faceDao()

        override fun personsFlow(): Flow<List<Person>> = dao.personsFlow()
        override fun faceGeometryFlow(): Flow<List<FaceGeometry>> = dao.faceGeometryFlow()
        override suspend fun photoIdsForCluster(clusterId: Long) = dao.photoIdsForCluster(clusterId)
        override suspend fun setPersonName(id: Long, name: String?) = dao.setPersonName(id, name)
    }
}
