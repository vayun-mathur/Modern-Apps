package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.FaceStore
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PhotoScanStore
import com.vayunmathur.photos.platform.PhotoStore
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.PhotoFace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cov-c2: GalleryViewModel search, trash/delete, people and album-cover flows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2VmTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var db: PhotoDatabase
    private var nextId = 9000L

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false, fav: Boolean = false) =
        Photo(
            id = nextId++, name = name, uri = "content://media/$name", date = date,
            width = 100, height = 100, dateModified = date, exifSet = true,
            lat = null, long = null, videoData = null, panoData = null,
            isTrashed = trashed, album = album, isFavorite = fav,
        )

    private fun vm(): GalleryViewModel {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries().build()
        return GalleryViewModel(app, FakePhotos(db), FakeScans(db), FakeFaces(db)).also { it.runSyncHook = {} }
    }

    private suspend fun awaitTrue(timeoutMs: Long = 5_000, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // Robolectric's main looper is paused; viewModelScope work needs it idled.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (check()) return
            Thread.sleep(50)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(check(), "condition not met within ${timeoutMs}ms")
    }

    private fun subscribe(vm: GalleryViewModel) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            launch { vm.photos.collect {} }
            launch { vm.albums.collect {} }
            launch { vm.people.collect {} }
            launch { vm.favorites.collect {} }
            launch { vm.faceBoxesByPhoto.collect {} }
            launch { vm.faceCountByPhoto.collect {} }
            launch { vm.searchResults.collect {} }
            launch { vm.indexTargetCount.collect {} }
        }
    }

    @Test fun searchFindsByNameAndClears() = runBlocking<Unit> {
        val vm = vm().also { subscribe(it) }
        db.photoDao().upsertAll(
            listOf(photo("cov-beach-day", 300L), photo("cov-mountain", 200L), photo("cov-beach-trash", 100L, trashed = true)),
        )
        awaitTrue { vm.photos.value.size == 3 }
        vm.setSearchQuery("beach")
        awaitTrue { vm.searchResults.value.isNotEmpty() }
        assertEquals(listOf("cov-beach-day"), vm.searchResults.value.map { it.name })
        vm.setSearchQuery("")
        awaitTrue { vm.searchResults.value.isEmpty() }
    }

    @Test fun deleteAndTrashUpdateDatabase() = runBlocking<Unit> {
        val vm = vm().also { subscribe(it) }
        val a = photo("cov-del-a", 300L)
        val b = photo("cov-del-b", 200L)
        db.photoDao().upsertAll(listOf(a, b))
        awaitTrue { vm.photos.value.size == 2 }
        vm.trashPhotoLocally(a)
        awaitTrue { db.photoDao().getAll().first { it.id == a.id }.isTrashed }
        vm.deletePhoto(b)
        awaitTrue { db.photoDao().getAll().none { it.id == b.id } }
    }

    @Test fun peopleFavoritesAndFaceBoxesFlows() = runBlocking<Unit> {
        val vm = vm().also { subscribe(it) }
        val a = photo("cov-ppl-a", 300L, fav = true)
        val b = photo("cov-ppl-b", 200L)
        db.photoDao().upsertAll(listOf(a, b))
        val ids = db.photoDao().getAll().map { it.id }
        val personId = db.faceDao().insertPerson(Person(centroid = ByteArray(8), faceCount = 2, name = "Ada"))
        db.faceDao().insertPhotoFaces(
            ids.map { pid ->
                PhotoFace(
                    photoId = pid, clusterId = personId, embedding = ByteArray(8), clusterable = true,
                    left = 0.1f, top = 0.1f, right = 0.6f, bottom = 0.6f, srcWidth = 100, srcHeight = 100,
                )
            },
        )
        awaitTrue { vm.people.value.any { it.name == "Ada" } }
        assertEquals(2, vm.people.value.first { it.name == "Ada" }.photos.size)
        awaitTrue { vm.favorites.value.map { it.name } == listOf("cov-ppl-a") }
        awaitTrue { vm.faceCountByPhoto.value.values.sum() == 2 }
        awaitTrue { vm.faceBoxesByPhoto.value.isNotEmpty() }
        val clusterPhotos = vm.photosForCluster(personId)
        assertEquals(2, clusterPhotos.size)
        assertTrue(vm.photosForCluster(999_999L).isEmpty())
        vm.setPersonName(personId, "Grace")
        awaitTrue { db.faceDao().personsFlow().first().first { it.id == personId }.name == "Grace" }
    }

    @Test fun albumCoverAndMoveBookkeeping() = runBlocking<Unit> {
        val vm = vm().also { subscribe(it) }
        val a = photo("cov-alb-a", 300L, album = "CovAlbum")
        val b = photo("cov-alb-b", 200L, album = "CovAlbum")
        db.photoDao().upsertAll(listOf(a, b))
        awaitTrue { vm.albums.value.any { it.name == "CovAlbum" && it.coverPhoto.id == a.id } }
        vm.setAlbumCover("CovAlbum", b.id)
        awaitTrue { vm.albums.value.first { it.name == "CovAlbum" }.coverPhoto.id == b.id }
        assertFalse(vm.consumePendingMove())
        vm.requestAlbumMove(emptyList(), "Elsewhere")
        assertTrue(vm.consumePendingMove())
        assertFalse(vm.consumePendingMove())
        vm.moveToAlbum(emptyList(), "Nowhere")
    }

    @Test fun runSyncUsesHookAndCountsFlow() = runBlocking<Unit> {
        val vm = vm().also { subscribe(it) }
        db.photoDao().upsertAll(listOf(photo("cov-cnt", 100L)))
        var syncs = 0
        vm.runSyncHook = { syncs++ }
        vm.runSync()
        assertEquals(1, syncs)
        awaitTrue { vm.indexTargetCount.value == 1 }
    }

    private class FakePhotos(db: PhotoDatabase) : PhotoStore {
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

    private class FakeScans(db: PhotoDatabase) : PhotoScanStore {
        private val dao = db.photoScanDao()
        override suspend fun getClipEmbeddings() = dao.getClipEmbeddings()
    }

    private class FakeFaces(db: PhotoDatabase) : FaceStore {
        private val dao = db.faceDao()
        override fun personsFlow(): Flow<List<Person>> = dao.personsFlow()
        override fun faceGeometryFlow(): Flow<List<com.vayunmathur.photos.data.FaceGeometry>> = dao.faceGeometryFlow()
        override suspend fun photoIdsForCluster(clusterId: Long) = dao.photoIdsForCluster(clusterId)
        override suspend fun setPersonName(id: Long, name: String?) = dao.setPersonName(id, name)
    }
}
