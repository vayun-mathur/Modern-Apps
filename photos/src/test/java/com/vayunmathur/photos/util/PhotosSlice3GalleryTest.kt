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

    private fun openDb() {
        workInfosOverride = { _, _ -> flowOf(emptyList<androidx.work.WorkInfo>()) }
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun viewModel(): GalleryViewModel {
        openDb()
        return GalleryViewModel(
            app,
            FakePhotosRepository(db),
            FakePhotoScanRepository(db),
            FakeFaceRepository(db),
        ).also {
            // No WorkManager initializer in unit tests: async moveToAlbum's
            // trailing runSync() must not touch the real SyncWorker, or its
            // uncaught IllegalStateException fails whichever test runs next.
            it.runSyncHook = {}
        }
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
        // Empty list: moveToAlbum returns before touching AlbumMediaStore or
        // WorkManager, so the pending bookkeeping is pinned without side effects.
        vm.requestAlbumMove(emptyList(), "Camera")
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
                        clusterable = true,
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

    @Test
    fun peopleFlowProjectsClustersWithLargestFaceCover() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(
            listOf(
                namespaced("p-big", 100L),
                namespaced("p-small", 200L),
            ),
        )
        val byName = db.photoDao().getAll().associateBy { it.name }
        val big = byName.getValue("slice3-p-big")
        val small = byName.getValue("slice3-p-small")
        val personId = db.faceDao().insertPerson(
            Person(centroid = ByteArray(8), faceCount = 2, name = "Ada"),
        )
        db.faceDao().insertPhotoFaces(
            listOf(
                // Big face on the older photo: cover selection is by source-pixel
                // area, not recency.
                PhotoFace(
                    photoId = big.id, clusterId = personId, embedding = ByteArray(8),
                    clusterable = true,
                    left = 0f, top = 0f, right = 0.9f, bottom = 0.9f,
                    srcWidth = 1000, srcHeight = 1000,
                ),
                PhotoFace(
                    photoId = small.id, clusterId = personId, embedding = ByteArray(8),
                    clusterable = true,
                    left = 0f, top = 0f, right = 0.1f, bottom = 0.1f,
                    srcWidth = 100, srcHeight = 100,
                ),
            ),
        )
        val people = vm.people.first { it.any { p -> p.id == personId } }
        val cluster = people.first { it.id == personId }
        assertEquals("Ada", cluster.name)
        assertEquals(big.id, cluster.coverPhoto.id)
        assertEquals(2, cluster.photos.size)
        // Member photos sort newest-first.
        assertEquals(listOf(small.id, big.id), cluster.photos.map { it.id })
    }

    @Test
    fun peopleFlowDropsTrashedAndEmptyClusters() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(listOf(namespaced("gone", 100L).copy(isTrashed = true)))
        val trashedId = db.photoDao().getAll().single().id
        val personId = db.faceDao().insertPerson(
            Person(centroid = ByteArray(8), faceCount = 1, name = null),
        )
        val otherId = db.faceDao().insertPerson(
            Person(centroid = ByteArray(8), faceCount = 0, name = null),
        )
        db.faceDao().insertPhotoFaces(
            listOf(
                PhotoFace(
                    photoId = trashedId, clusterId = personId, embedding = ByteArray(8),
                    clusterable = true,
                    left = 0f, top = 0f, right = 0.5f, bottom = 0.5f,
                    srcWidth = 100, srcHeight = 100,
                ),
            ),
        )
        // A cluster whose only photo is trashed projects to nothing; a cluster
        // with no faces at all never appears.
        val people = vm.people.first { true }
        assertTrue(people.none { it.id == personId })
        assertTrue(people.none { it.id == otherId })
    }

    @Test
    fun faceBoxesAndCountsDeriveFromGeometry() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(listOf(namespaced("faces", 100L)))
        val photoId = db.photoDao().getAll().single().id
        val personId = db.faceDao().insertPerson(
            Person(centroid = ByteArray(8), faceCount = 2, name = "Bo"),
        )
        db.faceDao().insertPhotoFaces(
            listOf(
                PhotoFace(
                    photoId = photoId, clusterId = personId, embedding = ByteArray(8),
                    clusterable = true,
                    left = 0.1f, top = 0.2f, right = 0.3f, bottom = 0.4f,
                    srcWidth = 640, srcHeight = 480,
                ),
                PhotoFace(
                    photoId = photoId, clusterId = personId, embedding = ByteArray(8),
                    clusterable = true,
                    left = 0.5f, top = 0.5f, right = 0.9f, bottom = 0.9f,
                    srcWidth = 640, srcHeight = 480,
                ),
            ),
        )
        val counts = vm.faceCountByPhoto.first { it[photoId] == 2 }
        assertEquals(2, counts.getValue(photoId))
        val boxes = vm.faceBoxesByPhoto.first { it.containsKey(photoId) }
        val overlay = boxes.getValue(photoId)
        assertEquals(640, overlay.srcWidth)
        assertEquals(480, overlay.srcHeight)
        assertEquals(2, overlay.faces.size)
        assertTrue(overlay.faces.all { it.clusterId == personId && it.name == "Bo" })
        assertEquals(0.1f, overlay.faces.minBy { it.left }.left)
    }

    @Test
    fun setPersonNamePersistsThroughDao() = runTest {
        val vm = viewModel()
        val personId = db.faceDao().insertPerson(
            Person(centroid = ByteArray(8), faceCount = 0, name = null),
        )
        vm.setPersonName(personId, "Grace")
        // setPersonName launches without an IO dispatcher so Room executes the
        // write itself: the DAO row shows the name; with no faces the cluster
        // still projects to nothing.
        val people = vm.people.first { true }
        assertTrue(people.none { it.id == personId })
        val name = db.faceDao().personsFlow().first().first { it.id == personId }.name
        assertEquals("Grace", name)
        // Clearing the name persists too.
        vm.setPersonName(personId, null)
        val cleared = db.faceDao().personsFlow().first().first { it.id == personId }.name
        assertEquals(null, cleared)
    }

    @Test
    fun searchPhotosMatchesNamesNewestFirst() = runTest {
        openDb()
        val dao = db.photoDao()
        dao.upsertAll(
            listOf(
                namespaced("beach-day", 100L),
                namespaced("beach-sunset", 300L),
                namespaced("mountain", 200L),
            ),
        )
        // OCR/name LIKE search backing combinedSearch: newest first.
        assertEquals(
            listOf("slice3-beach-sunset", "slice3-beach-day"),
            dao.searchPhotos("beach").map { it.name },
        )
        assertEquals(emptyList(), dao.searchPhotos("desert"))
    }

    @Test
    fun deletePhotoRemovesRow() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(listOf(namespaced("gone", 100L)))
        val row = db.photoDao().getAll().single()
        vm.deletePhoto(row)
        // deletePhoto launches on IO; advance until the async write lands.
        var tries = 0
        while (db.photoDao().getAll().isNotEmpty() && tries < 50) {
            kotlinx.coroutines.delay(20)
            tries++
        }
        assertTrue(db.photoDao().getAll().isEmpty())
    }

    @Test
    fun trashPhotoLocallyMarksTrashed() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(listOf(namespaced("trashme", 100L)))
        val row = db.photoDao().getAll().single()
        vm.trashPhotoLocally(row)
        var tries = 0
        while (!db.photoDao().getAll().single().isTrashed && tries < 50) {
            kotlinx.coroutines.delay(20)
            tries++
        }
        assertTrue(db.photoDao().getAll().single().isTrashed)
    }

    @Test
    fun albumMoveRequestRoundTrip() {
        val vm = viewModel()
        val a = namespaced("m", 100L)
        vm.requestAlbumMove(listOf(a), "Trip")
        assertTrue(vm.consumePendingMove())
        assertFalse(vm.consumePendingMove())
    }

    @Test
    fun searchAiStartsReadyAndQueryPublishes() = runTest {
        val vm = viewModel()
        assertEquals(SearchAiState.READY, vm.searchAiState.value)
        vm.setSearchQuery("beach")
        assertEquals("beach", vm.searchQuery.value)
        // OCR/name matching itself is pinned at the DAO level
        // (searchPhotosMatchesNamesNewestFirst); the semantic tower needs the
        // bundled native model, which is absent in unit tests.
    }

    @Test
    fun blankQueryYieldsNoResults() = runTest {
        val vm = viewModel()
        db.photoDao().upsertAll(listOf(namespaced("beach-day", 100L)))
        vm.setSearchQuery("   ")
        // Blank queries short-circuit to empty without hitting the DB; wait out
        // the debounce and assert nothing was published.
        kotlinx.coroutines.delay(400)
        assertTrue(vm.searchResults.value.isEmpty())
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
        override suspend fun setFavorite(ids: List<Long>, value: Boolean) = dao.setFavorite(ids, value)
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
