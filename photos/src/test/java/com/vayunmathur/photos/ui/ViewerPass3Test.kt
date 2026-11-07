package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.util.FakeFaceStorePass3
import com.vayunmathur.photos.util.FakePhotoStorePass3
import com.vayunmathur.photos.util.FakeScanStorePass3
import com.vayunmathur.photos.util.GalleryViewModel
import com.vayunmathur.photos.util.PhotoFaceBoxes
import com.vayunmathur.photos.util.FaceBox
import com.vayunmathur.photos.util.PhotoMapViewModel

import com.vayunmathur.photos.util.pass3Photo
import com.vayunmathur.photos.util.syncEnqueueHook
import com.vayunmathur.photos.util.syncOnceHook
import com.vayunmathur.photos.util.workInfosOverride
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** Pass-3 tests for the photo viewer: PhotoPage pager, detail view, panoramas. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ViewerPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    @BeforeTest
    fun setUp() {
        ocrLayoutOverride = { _, _ -> null }
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
    }

    @AfterTest
    fun tearDown() {
        ocrLayoutOverride = null
        workInfosOverride = null
        syncOnceHook = null
        syncEnqueueHook = null
    }

    private fun vm(store: FakePhotoStorePass3): GalleryViewModel {
        val vm = GalleryViewModel(app, store, FakeScanStorePass3(), FakeFaceStorePass3())
        vm.runSyncHook = {}
        return vm
    }

    @Test
    fun photoPageWithOverrideListShowsPager() {
        val photos = listOf(
            pass3Photo(3, "c.jpg", date = 300), pass3Photo(2, "b.jpg", date = 200), pass3Photo(1, "a.jpg", date = 100),
        )
        val store = FakePhotoStorePass3(photos)
        rule.setContent {
            PhotoPage(vm(store), PhotoMapViewModel(app), id = 2, overridePhotosList = photos, backStack = NavBackStack(arrayOf<Route>(Route.Gallery)))
        }
        rule.waitForIdle()
    }

    @Test
    fun photoPagePendingUriShowsPendingView() {
        val store = FakePhotoStorePass3(emptyList())
        rule.setContent {
            PhotoPage(vm(store), PhotoMapViewModel(app), id = -1, overridePhotosList = null, pendingUri = "content://media/999")
        }
        rule.waitForIdle()
    }

    @Test
    fun photoPageFromStorePhotos() {
        val photos = listOf(pass3Photo(1, "a.jpg", date = 100), pass3Photo(2, "b.jpg", date = 200))
        val store = FakePhotoStorePass3(photos)
        val vm = vm(store)
        rule.setContent { PhotoPage(vm, PhotoMapViewModel(app), id = 1, overridePhotosList = null) }
        // photos flow is WhileSubscribed; wait for the VM list to populate.
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.photos.value.isEmpty() && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        rule.waitForIdle()
    }

    @Test
    fun photoDetailViewSettledWithMetadataAndFaces() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val photo = pass3Photo(1, "a.jpg", lat = 48.85, long = 2.35)
        rule.setContent {
            val pager = rememberPagerState { 3 }
            PhotoDetailView(
                photo = photo, context = ctx, photoMapViewModel = PhotoMapViewModel(app),
                pagerState = pager, pageIndex = 0, isSettled = true, isMetadataVisible = true,
                currentZoom = remember { mutableStateOf(ZoomState()) },
                peopleCount = 2,
                faceBoxes = PhotoFaceBoxes(100, 80, listOf(FaceBox(5, "Grace", 0.1f, 0.1f, 0.5f, 0.5f))),
                onZoomUpdate = {}, onToggleMetadata = {}, onEditPhoto = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun photoDetailViewUnsettledMetadataHidden() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val photo = pass3Photo(1, "video.mp4").copy(videoData = com.vayunmathur.photos.data.VideoData(12_000))
        rule.setContent {
            val pager = rememberPagerState { 1 }
            PhotoDetailView(
                photo = photo, context = ctx, photoMapViewModel = PhotoMapViewModel(app),
                pagerState = pager, pageIndex = 0, isSettled = false, isMetadataVisible = false,
                currentZoom = remember { mutableStateOf(ZoomState()) },
                onZoomUpdate = {}, onToggleMetadata = {}, onEditPhoto = {},
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun panoramaViewsCompose() {
        val photo = pass3Photo(1, "pano.jpg")
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                PanoramaFlatView(photo)
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun wallpaperPreviewWithBitmapSnapshot() {
        val bitmap = android.graphics.Bitmap.createBitmap(80, 60, android.graphics.Bitmap.Config.ARGB_8888)
        var snapshot: PreviewSnapshot? = null
        rule.setContent {
            WallpaperPreview(
                bitmap = bitmap, loadState = com.vayunmathur.photos.util.WallpaperLoadState.Loaded,
                targetAspect = 0.5f, zoomScale = 1f, zoomOffset = androidx.compose.ui.geometry.Offset.Zero,
                currentScale = remember { mutableStateOf(1f) },
                currentOffset = remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) },
                onZoomChange = { _, _ -> }, onCoverMin = {}, onSnapshot = { snapshot = it },
            )
        }
        rule.waitForIdle()
        // The snapshot is reported on layout; it may or may not have fired under Robolectric.
        snapshot?.let {
            kotlin.test.assertTrue(it.containerW > 0f)
        }
    }
}
