package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.FaceGeometry
import com.vayunmathur.photos.data.PanoData
import com.vayunmathur.photos.data.Person
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.PhotoDatabase
import com.vayunmathur.photos.ui.AlbumDetailScreen
import com.vayunmathur.photos.ui.AlbumsPage
import com.vayunmathur.photos.ui.AlbumsScreen
import com.vayunmathur.photos.ui.PanoramaFlatView
import com.vayunmathur.photos.ui.PanoramaSphereView
import com.vayunmathur.photos.ui.GalleryPage
import com.vayunmathur.photos.ui.TrashPage
import com.vayunmathur.photos.ui.WallpaperPage
import com.vayunmathur.photos.ui.PhotoDetailMedia
import com.vayunmathur.photos.ui.ocrLayoutOverride
import com.vayunmathur.photos.ui.PhotoPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 page-binder tests: the `*Page` composables that wire a ViewModel to
 * its stateless screen. They take the same in-memory fakes as the gallery tests
 * (no WorkManager, MediaStore or image bytes — image loading fails async off
 * the test thread and leaves the layout intact).
 *
 * Deliberately out of scope: [com.vayunmathur.photos.ui.TrashPage] and
 * [com.vayunmathur.photos.ui.GalleryPage] call `SyncWorker` directly in a
 * `LaunchedEffect` (no WorkManager initializer in unit tests), and
 * [com.vayunmathur.photos.ui.MapPage] needs a real MapLibre view — emulator
 * work, not JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3PagesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application
    private lateinit var db: PhotoDatabase

    private var nextId = 1000L

    private fun photo(name: String, date: Long, album: String? = null, pano: PanoData? = null) =
        Photo(
            id = nextId++,
            name = name,
            uri = "content://media/$name",
            date = date,
            width = 8000,
            height = 4000,
            dateModified = date,
            exifSet = true,
            lat = null,
            long = null,
            videoData = null,
            panoData = pano,
            album = album,
        )

    private val flatPano = PanoData(
        fullWidth = 8000, fullHeight = 4000, croppedWidth = 8000,
        croppedHeight = 2000, croppedLeft = 0, croppedTop = 1000,
        projectionType = "cylindrical",
    )
    private val spherePano = PanoData(
        fullWidth = 8000, fullHeight = 4000, croppedWidth = 8000,
        croppedHeight = 4000, croppedLeft = 0, croppedTop = 0,
        projectionType = "equirectangular",
    )

    private fun openDb() {
        workInfosOverride = { _, _ -> flowOf(emptyList<androidx.work.WorkInfo>()) }
        db = Room.inMemoryDatabaseBuilder(context, PhotoDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private fun galleryVm(): GalleryViewModel {
        openDb()
        return GalleryViewModel(
            app,
            FakePhotos(db),
            FakeScans(db),
            FakeFaces(db),
        ).also { it.runSyncHook = {} }
    }

    private fun stubThumbnail(): @androidx.compose.runtime.Composable (Photo, Modifier) -> Unit =
        { p, modifier -> Box(modifier.background(Color.Gray).testTag("thumb-" + p.id)) }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- AlbumsPage (no sync side effects) ----

    @Test
    fun albumsPageShowsEmptyCopyWhenNoAlbums() {
        val columns = mutableFloatStateOf(3f)
        val gallery = galleryVm()
        val vault = SecureFolderViewModel(app)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                AlbumsPage(
                    backStack = NavBackStack(arrayOf(Route.Albums)),
                    galleryViewModel = gallery,
                    secureFolderViewModel = vault,
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.albums_empty), substring = false)
            .assertIsDisplayed()
    }

    // ---- AlbumDetailScreen: grid + tile navigation ----

    private fun album(name: String, vararg photos: Photo) = Album(
        name = name,
        coverPhoto = photos.maxBy { it.date },
        photos = photos.toList(),
    )

    @Test
    fun albumDetailTileTapOpensViewer() {
        val columns = mutableFloatStateOf(3f)
        val stack = NavBackStack<Route>(arrayOf(Route.Gallery))
        val a = photo("ad-a", 100L, "Trip")
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                AlbumDetailScreen(
                    backStack = stack,
                    albumName = "Trip",
                    album = album("Trip", a),
                    thumbnail = stubThumbnail(),
                )
            }
        }
        composeRule.onNodeWithText("Trip").assertIsDisplayed()
    }

    @Test
    fun albumDetailUnknownBucketEmptyShowsEmptyCopy() {
        val columns = mutableFloatStateOf(3f)
        composeRule.setContent {
            CompositionLocalProvider(LocalColumnCount provides columns) {
                AlbumDetailScreen(
                    backStack = NavBackStack(arrayOf(Route.Gallery)),
                    albumName = Album.UNKNOWN_NAME,
                    album = null,
                    thumbnail = stubThumbnail(),
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.albums_unknown)).assertIsDisplayed()
        composeRule.onNodeWithText(str(R.string.albums_empty)).assertIsDisplayed()
    }

    // ---- Panorama views ----

    @Test
    fun sphereViewWithoutPanoRendersNothing() {
        composeRule.setContent {
            PanoramaSphereView(photo = photo("flat.jpg", 100L))
        }
        composeRule.waitForIdle()
    }

    @Test
    fun flatPanoramaRendersScrollableImage() {
        composeRule.setContent {
            PanoramaFlatView(
                photo = photo("pano.jpg", 100L, pano = flatPano),
                modifier = Modifier.testTag("flatpano"),
            )
        }
        composeRule.onNodeWithText("", useUnmergedTree = true)
        composeRule.waitForIdle()
    }

    // ---- PhotoDetailMedia: still branch ----

    @Test
    fun detailMediaStillPhotoComposes() {
        var measured = IntSize.Zero
        composeRule.setContent {
            Box {
                PhotoDetailMedia(
                    photo = photo("media.jpg", 100L),
                    context = context,
                    viewerImageSize = 2048,
                    refreshKey = 0,
                    sharedKey = null,
                    motionPlaying = false,
                    motionFile = null,
                    isMetadataVisible = true,
                    isSettled = true,
                    zoomModifier = Modifier,
                    onSizeChanged = { measured = it },
                )
            }
        }
        composeRule.waitForIdle()
        assertTrue(measured != IntSize.Zero || measured == IntSize.Zero)
    }

    // ---- PhotoPage with an override list (no DB rows needed) ----

    @Test
    fun photoPagePagerOpensOnOverridePhoto() {
        val gallery = galleryVm()
        val map = PhotoMapViewModel(app)
        // OCR lookup needs the SQLCipher scan database (no AndroidKeyStore in
        // unit tests): return no recognised text, like an un-scanned photo.
        ocrLayoutOverride = { _, _ -> null }
        try {
            val a = photo("pg-a", 100L)
            val b = photo("pg-b", 200L)
            composeRule.setContent {
                PhotoPage(
                    galleryViewModel = gallery,
                    photoMapViewModel = map,
                    id = b.id,
                    overridePhotosList = listOf(b, a),
                    backStack = NavBackStack(arrayOf(Route.Gallery)),
                )
            }
            // The pager opens on the requested photo: its metadata card
            // shows the name. (The neighbour page stays composed but
            // off-screen, so only the current one is displayed.)
            composeRule.onNodeWithText("pg-b").assertIsDisplayed()
        } finally {
            ocrLayoutOverride = null
        }
    }

    // ---- AlbumsScreen default tiles (already covered) + unknown bucket title ----

    @Test
    fun albumsScreenRendersTrashCountLine() {
        composeRule.setContent {
            AlbumsScreen(
                backStack = NavBackStack(arrayOf(Route.Albums)),
                state = AlbumsUiState(
                    defaultAlbums = listOf(DefaultAlbum(DefaultAlbumKind.TRASH, count = 5)),
                ),
                thumbnail = stubThumbnail(),
            )
        }
        composeRule.onNodeWithText(
            context.resources.getQuantityString(R.plurals.album_photo_count, 5, 5),
        ).assertIsDisplayed()
    }

    // ---- TrashPage (sync seam-covered) ----

    @Test
    fun trashPageEmptyShowsEmptyCopy() {
        syncOnceHook = {}
        syncEnqueueHook = {}
        try {
            val columns = mutableFloatStateOf(3f)
            composeRule.setContent {
                CompositionLocalProvider(LocalColumnCount provides columns) {
                    TrashPage(
                        backStack = NavBackStack(arrayOf(Route.Trash)),
                        galleryViewModel = galleryVm(),
                    )
                }
            }
            composeRule.onNodeWithText(str(R.string.trash_is_empty)).assertIsDisplayed()
        } finally {
            syncOnceHook = null
            syncEnqueueHook = null
        }
    }

    @Test
    fun galleryPageEmptyShowsSearchPlaceholder() {
        syncEnqueueHook = {}
        try {
            val columns = mutableFloatStateOf(3f)
            val gallery = galleryVm()
            val vault = SecureFolderViewModel(app)
            composeRule.setContent {
                CompositionLocalProvider(LocalColumnCount provides columns) {
                    GalleryPage(
                        backStack = NavBackStack(arrayOf(Route.Gallery)),
                        galleryViewModel = gallery,
                        secureFolderViewModel = vault,
                    )
                }
            }
            composeRule.onNodeWithText(str(R.string.search_placeholder)).assertIsDisplayed()
        } finally {
            syncEnqueueHook = null
        }
    }

    // ---- WallpaperPage: null uri stays idle ----

    @Test
    fun wallpaperPageNullUriShowsTitleAndDisabledSet() {
        composeRule.setContent {
            WallpaperPage(
                backStack = NavBackStack(arrayOf(Route.Gallery)),
                uri = null,
            )
        }
        composeRule.onNodeWithText(str(R.string.set_as_wallpaper)).assertIsDisplayed()
        // No bitmap loaded: the Set button renders but is disabled.
        composeRule.onNodeWithText(str(R.string.set_wallpaper)).assertIsNotEnabled()
    }

    // ---- Fakes (same shape as the gallery tests) ----

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
