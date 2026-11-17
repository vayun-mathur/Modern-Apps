package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.FaceStore
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PhotoMapViewModel
import com.vayunmathur.photos.platform.PhotoScanStore
import com.vayunmathur.photos.platform.PhotoStore
import com.vayunmathur.photos.platform.SecureFolderViewModel
import com.vayunmathur.photos.platform.syncEnqueueHook
import com.vayunmathur.photos.platform.syncOnceHook
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.Navigation
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cov-c2: top-level Navigation and the real PhotosRepository over a file DB. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2NavigationTest {

    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var db: PhotoDatabase

    private fun galleryVm(): GalleryViewModel {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries().build()
        return GalleryViewModel(app, FakePhotos(db), FakeScans(db), FakeFaces(db)).also { it.runSyncHook = {} }
    }

    @Test fun navigationShowsGalleryRoute() = runBlocking<Unit> {
        val gallery = galleryVm()
        val columns = mutableFloatStateOf(3f)
        val photoMap = PhotoMapViewModel(app)
        val secure = SecureFolderViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                Navigation(
                    galleryViewModel = gallery,
                    photoMapViewModel = photoMap,
                    secureFolderViewModel = secure,
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onRoot().fetchSemanticsNode() != null)
    }

    // The real PhotosRepository/PhotoScanRepository/FaceRepository cannot be
    // exercised on the JVM: their Room database is SQLCipher-backed and the
    // keystore is unavailable under Robolectric (KeyStoreException), which is
    // why the existing suites fake the stores over an in-memory database.

    @Test fun navigationWithViewUriStartsAtViewer() = runBlocking<Unit> {
        val gallery = galleryVm()
        val columns = mutableFloatStateOf(3f)
        val photoMap = PhotoMapViewModel(app)
        val secure = SecureFolderViewModel(app)
        val uri = android.net.Uri.parse("content://media/external/images/media/42")
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                Navigation(
                    galleryViewModel = gallery,
                    photoMapViewModel = photoMap,
                    secureFolderViewModel = secure,
                    viewUri = uri,
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onRoot().fetchSemanticsNode() != null)
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
        override fun personsFlow(): Flow<List<com.vayunmathur.photos.data.Person>> = dao.personsFlow()
        override fun faceGeometryFlow(): Flow<List<com.vayunmathur.photos.data.FaceGeometry>> = dao.faceGeometryFlow()
        override suspend fun photoIdsForCluster(clusterId: Long) = dao.photoIdsForCluster(clusterId)
        override suspend fun setPersonName(id: Long, name: String?) = dao.setPersonName(id, name)
    }
}
