package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Navigation
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.pushPhoto
import com.vayunmathur.photos.util.FakeFaceStorePass3
import com.vayunmathur.photos.util.FakePhotoStorePass3
import com.vayunmathur.photos.util.FakeScanStorePass3
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PhotoMapViewModel
import com.vayunmathur.photos.util.SecureFolderViewModel
import com.vayunmathur.photos.util.WallpaperLoadState
import com.vayunmathur.photos.util.pass3Photo
import com.vayunmathur.photos.util.syncEnqueueHook
import com.vayunmathur.photos.util.syncOnceHook
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pass-3 tests for Map/Wallpaper/Video screens, [Navigation] and route helpers. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScreensPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    @BeforeTest
    fun setUp() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
    }

    @AfterTest
    fun tearDown() {
        workInfosOverride = null
        syncOnceHook = null
        syncEnqueueHook = null
    }

    private fun galleryVm(store: FakePhotoStorePass3 = FakePhotoStorePass3()): GalleryViewModel {
        val vm = GalleryViewModel(app, store, FakeScanStorePass3(), FakeFaceStorePass3())
        vm.runSyncHook = {}
        return vm
    }

    private fun backStack(vararg routes: Route) = NavBackStack(if (routes.isEmpty()) arrayOf<Route>(Route.Gallery) else routes)

    @Test
    fun clusterPhotosGroupsNearbyAndSorts() {
        val p1 = pass3Photo(1, "a.jpg", date = 100)
        val p2 = pass3Photo(2, "b.jpg", date = 300)
        val p3 = pass3Photo(3, "c.jpg", date = 200)
        val items = listOf(
            Triple(DpOffset(0.dp, 0.dp), GeoPoint(10.0, 20.0), p1),
            Triple(DpOffset(10.dp, 10.dp), GeoPoint(10.1, 20.1), p2),
            Triple(DpOffset(500.dp, 500.dp), GeoPoint(30.0, 40.0), p3),
        )
        val clusters = PhotoMapViewModel.clusterPhotos(items, 50.dp)
        assertEquals(2, clusters.size)
        val big = clusters.first { it.count == 2 }
        assertEquals(listOf(2L, 1L), big.allPhotos.map { it.id })
        assertEquals(GeoPoint(10.0, 20.0), big.position)
        assertTrue(PhotoMapViewModel.clusterPhotos(emptyList(), 50.dp).isEmpty())
    }

    @Test
    fun regenerateClustersPublishes() {
        val vm = PhotoMapViewModel(app)
        val p1 = pass3Photo(1, "a.jpg")
        vm.regenerateClusters(listOf(Triple(DpOffset(0.dp, 0.dp), GeoPoint(1.0, 2.0), p1)))
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.generatedClusters.value.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(1, vm.generatedClusters.value.size)
        // A country request for a photo with no geocoder backend resolves without crashing.
        vm.requestCountryName(1, 48.85, 2.35)
    }

    @Test
    fun mapPageComposesWithPhotos() {
        val store = FakePhotoStorePass3(
            listOf(pass3Photo(1, "geo.jpg", lat = 48.85, long = 2.35), pass3Photo(2, "plain.jpg")),
        )
        rule.setContent { MapPage(backStack(), galleryVm(store), PhotoMapViewModel(app)) }
        rule.waitForIdle()
    }

    @Test
    fun videoPlayerComposesSettledAndUnsettled() {
        rule.setContent {
            VideoPlayer(
                modifier = androidx.compose.ui.Modifier,
                uri = Uri.parse("content://media/123"),
                isMetadataVisible = true,
                isSettledPage = false,
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun formatVideoTimeValues() {
        assertEquals("0:00", formatVideoTime(0))
        assertEquals("1:05", formatVideoTime(65_000))
        assertEquals("1:02:03", formatVideoTime(3_723_000))
    }

    @Test
    fun wallpaperPageWithoutUriRenders() {
        rule.setContent { WallpaperPage(backStack(), null) }
        rule.waitForIdle()
    }

    @Test
    fun wallpaperPreviewStates() {
        val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        // Failed state shows the failure message.
        rule.setContent {
            WallpaperPreview(
                bitmap = null, loadState = WallpaperLoadState.Failed, targetAspect = 0.5f,
                zoomScale = 1f, zoomOffset = Offset.Zero,
                currentScale = remember { mutableStateOf(1f) },
                currentOffset = remember { mutableStateOf(Offset.Zero) },
                onZoomChange = { _, _ -> }, onCoverMin = {}, onSnapshot = {},
            )
        }
        rule.onNodeWithText("Failed to load image", substring = true).assertExists()
    }

    @Test
    fun wallpaperPreviewLoadingState() {
        rule.setContent {
            WallpaperPreview(
                bitmap = null, loadState = WallpaperLoadState.Loading, targetAspect = 0.5f,
                zoomScale = 1f, zoomOffset = Offset.Zero,
                currentScale = remember { mutableStateOf(1f) },
                currentOffset = remember { mutableStateOf(Offset.Zero) },
                onZoomChange = { _, _ -> }, onCoverMin = {}, onSnapshot = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun navigationGalleryRootComposes() {
        val gallery = galleryVm(FakePhotoStorePass3(listOf(pass3Photo(1, "a.jpg"))))
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
                Navigation(gallery, PhotoMapViewModel(app), SecureFolderViewModel(app))
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun navigationViewUriRootComposes() {
        val gallery = galleryVm()
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
                Navigation(
                    gallery, PhotoMapViewModel(app), SecureFolderViewModel(app),
                    viewUri = Uri.parse("content://media/external/images/media/42"),
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun pushPhotoReplacesViewerRoute() {
        val stack = NavBackStack(arrayOf<Route>(Route.Gallery))
        stack.pushPhoto(Route.PhotoPage(1, null))
        assertEquals(2, stack.backStack.size)
        stack.pushPhoto(Route.PhotoPage(2, null))
        assertEquals(2, stack.backStack.size)
        assertEquals(2L, (stack.backStack.last() as Route.PhotoPage).id)
        stack.add(Route.Map)
        stack.pushPhoto(Route.PhotoPage(3, null))
        assertEquals(4, stack.backStack.size)
    }

    @Test
    fun routeSerializationRoundTrips() {
        val routes: List<Route> = listOf(
            Route.Gallery, Route.Map, Route.People, Route.Albums, Route.Trash, Route.SecureFolder,
            Route.AlbumDetail("Trips"), Route.PhotoPage(7, null, "content://x"),
            Route.VaultViewer(3), Route.Wallpaper(9, "content://y"),
        )
        for (route in routes) {
            val encoded = Json.encodeToString(Route.serializer(), route)
            assertEquals(route.toString(), Json.decodeFromString(Route.serializer(), encoded).toString())
        }
    }
}
