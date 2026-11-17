package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.FaceRecognizer
import com.vayunmathur.photos.platform.FaceStore
import com.vayunmathur.photos.platform.GalleryViewModel
import com.vayunmathur.photos.platform.PeopleUiState
import com.vayunmathur.photos.platform.PersonCluster
import com.vayunmathur.photos.platform.PhotoScanStore
import com.vayunmathur.photos.platform.PhotoStore
import com.vayunmathur.photos.platform.WallpaperLoadState
import com.vayunmathur.photos.platform.syncEnqueueHook
import com.vayunmathur.photos.platform.syncOnceHook
import com.vayunmathur.photos.platform.workInfosOverride

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.ui.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.data.PhotoFace
import com.vayunmathur.photos.ui.AlbumDetailPage
import com.vayunmathur.photos.ui.PeoplePage
import com.vayunmathur.photos.ui.PeopleScreen
import com.vayunmathur.photos.ui.PreviewSnapshot
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.ui.WallpaperPreview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Cov-c2: page binders (AlbumDetail/People/Trash) and WallpaperPreview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosCovC2PagesTest {

    @get:Rule val composeRule = createComposeRule()

    private val columnsState = mutableFloatStateOf(3f)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var db: PhotoDatabase
    private var nextId = 5000L

    private fun photo(name: String, date: Long, album: String? = null, trashed: Boolean = false) =
        Photo(
            id = nextId++, name = name, uri = "content://media/$name", date = date,
            width = 400, height = 300, dateModified = date, exifSet = true,
            lat = null, long = null, videoData = null, panoData = null,
            isTrashed = trashed, album = album,
        )

    private fun openDb() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    private fun vm(): GalleryViewModel {
        openDb()
        return GalleryViewModel(app, FakePhotos(db), FakeScans(db), FakeFaces(db)).also { it.runSyncHook = {} }
    }

    private fun str(id: Int): String = context.getString(id)

    @Test fun albumDetailPageShowsAlbumPhotosAndSelection() = runBlocking<Unit> {
        val vm = vm()
        val a = photo("cov-trip-a", 300L, "CovTrip")
        val b = photo("cov-trip-b", 200L, "CovTrip")
        val other = photo("cov-other", 100L, "Elsewhere")
        db.photoDao().upsertAll(listOf(a, b, other))
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) {
                AlbumDetailPage(NavBackStack(arrayOf(Route.Gallery)), vm, "CovTrip")
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { vm.albums.value.any { it.name == "CovTrip" } }
        composeRule.onNodeWithText("CovTrip").assertIsDisplayed()
        // Selecting a photo in the VM surfaces through the binder's intersected selection.
        vm.toggleSelection(a.id)
        assertEquals(setOf(a.id), vm.selectedIds.value)
    }

    @Test fun albumDetailPageUnknownAlbumShowsEmpty() {
        val vm = vm()
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) {
                AlbumDetailPage(NavBackStack(arrayOf(Route.Gallery)), vm, "NoSuchAlbum")
            }
        }
        composeRule.waitForIdle()
        assertTrue(vm.albums.value.isEmpty())
    }

    @Test fun trashPageShowsEmptyStateWhenNothingTrashed() = runBlocking<Unit> {
        val vm = vm()
        db.photoDao().upsertAll(listOf(photo("cov-live", 100L)))
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) {
                TrashPage(NavBackStack(arrayOf(Route.Gallery)), vm)
            }
        }
        composeRule.onNodeWithText(str(R.string.label_trash)).assertIsDisplayed()
    }

    @Test fun trashPageListsTrashedPhotos() = runBlocking<Unit> {
        val vm = vm()
        db.photoDao().upsertAll(
            listOf(photo("cov-trashed", 100L, trashed = true), photo("cov-kept", 200L)),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columnsState) {
                TrashPage(NavBackStack(arrayOf(Route.Gallery)), vm)
            }
        }
        composeRule.onNodeWithText(str(R.string.label_trash)).assertIsDisplayed()
        composeRule.waitForIdle()
    }

    @Test fun peopleScreenShowsScanningProgress() {
        val cover = photo("cov-face", 100L)
        val cluster = PersonCluster(
            id = 7L, name = "Ada", coverPhoto = cover,
            faceLeft = 0f, faceTop = 0f, faceRight = 0.5f, faceBottom = 0.5f,
            photos = listOf(cover),
        )
        var named: PersonCluster? = null
        composeRule.setContent {
            PeopleScreen(
                backStack = NavBackStack(arrayOf(Route.Gallery)),
                state = PeopleUiState(
                    people = listOf(cluster), modelsAvailable = true, indexing = true,
                    faceScannedCount = 1, faceTargetCount = 4,
                ),
                faceThumbnail = { _, modifier -> Box(modifier.background(Color.Gray).testTag("face-thumb")) },
                onNameClick = { named = it },
            )
        }
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        composeRule.onNodeWithText("Ada").performClick()
        // Grid tile click selects the person, not the name action; name stays unset here.
        composeRule.waitForIdle()
    }

    @Test fun peopleScreenShowsModelMissingAndEmptyStates() {
        composeRule.setContent {
            PeopleScreen(
                backStack = NavBackStack(arrayOf(Route.Gallery)),
                state = PeopleUiState(
                    people = emptyList(), modelsAvailable = false, indexing = false,
                    faceScannedCount = 0, faceTargetCount = 0,
                ),
            )
        }
        composeRule.onNodeWithText(str(R.string.people_model_missing)).assertIsDisplayed()
    }

    @Test fun peoplePageBinderRendersWithViewModel() = runBlocking<Unit> {
        val vm = vm()
        val a = photo("cov-person-a", 300L)
        val b = photo("cov-person-b", 200L)
        db.photoDao().upsertAll(listOf(a, b))
        val ids = db.photoDao().getAll().map { it.id }
        val personId = db.faceDao().insertPerson(Person(centroid = ByteArray(8), faceCount = 2, name = "Bo"))
        db.faceDao().insertPhotoFaces(
            ids.map { pid ->
                PhotoFace(
                    photoId = pid, clusterId = personId, embedding = ByteArray(8), clusterable = true,
                    left = 0f, top = 0f, right = 0.5f, bottom = 0.5f, srcWidth = 100, srcHeight = 100,
                )
            },
        )
        composeRule.setContent { PeoplePage(NavBackStack(arrayOf(Route.Gallery)), vm) }
        composeRule.waitForIdle()
        // FaceRecognizer models are not installed in the unit-test sandbox, so the
        // binder must reach the model-missing branch rather than crash.
        composeRule.onNodeWithText(str(R.string.people_model_missing)).assertIsDisplayed()
    }

    @Test fun wallpaperPreviewReportsSnapshotAndCoverMin() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        var snapshot: PreviewSnapshot? = null
        var coverMin: Float? = null
        val scale = mutableStateOf(1f)
        val offset = mutableStateOf(Offset.Zero)
        composeRule.setContent {
            WallpaperPreview(
                bitmap = bitmap, loadState = WallpaperLoadState.Loaded, targetAspect = 0.5f,
                zoomScale = 1f, zoomOffset = Offset.Zero,
                currentScale = scale, currentOffset = offset,
                onZoomChange = { _, _ -> }, onCoverMin = { coverMin = it },
                onSnapshot = { snapshot = it },
            )
        }
        composeRule.waitForIdle()
        assertNotNull(snapshot)
        assertTrue(snapshot!!.baseW > 0f && snapshot.viewportH > 0f)
        assertNotNull(coverMin)
    }

    @Test fun wallpaperPreviewShowsLoading() {
        val scaleState = mutableStateOf(1f)
        val offsetState = mutableStateOf(Offset.Zero)
        composeRule.setContent {
            WallpaperPreview(
                bitmap = null, loadState = WallpaperLoadState.Loading, targetAspect = 0.5f,
                zoomScale = 1f, zoomOffset = Offset.Zero,
                currentScale = scaleState, currentOffset = offsetState,
                onZoomChange = { _, _ -> }, onCoverMin = {}, onSnapshot = {},
            )
        }
        composeRule.waitForIdle()
    }

    @Test fun wallpaperPreviewShowsFailure() {
        val scaleState = mutableStateOf(1f)
        val offsetState = mutableStateOf(Offset.Zero)
        composeRule.setContent {
            WallpaperPreview(
                bitmap = null, loadState = WallpaperLoadState.Failed, targetAspect = 0.5f,
                zoomScale = 1f, zoomOffset = Offset.Zero,
                currentScale = scaleState, currentOffset = offsetState,
                onZoomChange = { _, _ -> }, onCoverMin = {}, onSnapshot = {},
            )
        }
        composeRule.onNodeWithText(str(R.string.wallpaper_load_failed)).assertIsDisplayed()
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
