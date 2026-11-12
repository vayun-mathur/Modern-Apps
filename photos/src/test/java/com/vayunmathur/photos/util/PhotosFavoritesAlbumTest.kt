package com.vayunmathur.photos.util

import android.app.Application
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #912: what the Favorites album holds, read through [GalleryViewModel.favorites]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosFavoritesAlbumTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private var nextId = 1L

    private fun photo(name: String, date: Long, favorite: Boolean = false, trashed: Boolean = false) = Photo(
        id = nextId++,
        name = name,
        uri = "content://media/external/images/media/$name",
        date = date,
        width = 100,
        height = 100,
        dateModified = date,
        exifSet = false,
        lat = null,
        long = null,
        videoData = null,
        panoData = null,
        isTrashed = trashed,
        isFavorite = favorite,
    )

    // ---- Favorites album contents ----

    @Test
    fun favoritesAreFavoritedUntrashedItemsNewestFirst() = runTest {
        workInfosOverride = { _, _ -> flowOf(emptyList<androidx.work.WorkInfo>()) }
        val db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.photoDao().upsertAll(
            listOf(
                photo("old-fav", 100L, favorite = true),
                photo("new-fav", 300L, favorite = true),
                photo("trashed-fav", 400L, favorite = true, trashed = true),
                photo("plain", 500L),
            ),
        )
        val vm = GalleryViewModel(
            context.applicationContext as Application,
            FakePhotos(db),
            FakeScans(db),
            FakeFaces(db),
        ).also { it.runSyncHook = {} }

        val favorites = vm.favorites.first { it.isNotEmpty() }

        assertEquals(listOf("new-fav", "old-fav"), favorites.map { it.name })
    }

    // ---- Store fakes (same shape as the gallery tests) ----

    private class FakePhotos(db: PhotoDatabase) : PhotoStore {
        private val dao = db.photoDao()
        override fun getAllFlow(): Flow<List<Photo>> = dao.getAllFlow()
        override fun getOCRCountFlow(): Flow<Int> = dao.getOCRCountFlow()
        override fun getClipCountFlow(): Flow<Int> = dao.getClipCountFlow()
        override fun getFaceScannedCountFlow(): Flow<Int> = dao.getFaceScannedCountFlow()
        override fun getIndexTargetCountFlow(): Flow<Int> = dao.getIndexTargetCountFlow()
        override suspend fun getByUri(uri: String): List<Photo> = dao.getByUri(uri)
        override suspend fun searchPhotos(query: String): List<Photo> = dao.searchPhotos(query)
        override suspend fun delete(photo: Photo): Int = dao.delete(photo)
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
        override fun faceGeometryFlow(): Flow<List<FaceGeometry>> = dao.faceGeometryFlow()
        override suspend fun photoIdsForCluster(clusterId: Long) = dao.photoIdsForCluster(clusterId)
        override suspend fun setPersonName(id: Long, name: String?) = dao.setPersonName(id, name)
    }
}
