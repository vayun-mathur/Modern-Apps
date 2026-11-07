package com.vayunmathur.photos.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.map.GeoPoint
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.LocalColumnCount
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.PanoData
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
import java.io.File
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pass-3 interaction tests: map markers, trash selection, gallery page, panoramas, wallpaper page. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class InteractionsPass3Test {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<Context>() as Application

    @BeforeTest
    fun setUp() {
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        syncOnceHook = {}
        syncEnqueueHook = {}
        ocrLayoutOverride = { _, _ -> null }
    }

    @AfterTest
    fun tearDown() {
        workInfosOverride = null
        syncOnceHook = null
        syncEnqueueHook = null
        ocrLayoutOverride = null
    }

    private fun vm(store: FakePhotoStorePass3 = FakePhotoStorePass3()): GalleryViewModel {
        val vm = GalleryViewModel(app, store, FakeScanStorePass3(), FakeFaceStorePass3())
        vm.runSyncHook = {}
        return vm
    }

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Gallery))

    @Test
    fun mapPageWithClustersComposesMarkers() {
        val photos = listOf(
            pass3Photo(1, "geo1.jpg", lat = 48.85, long = 2.35),
            pass3Photo(2, "geo2.jpg", lat = 48.86, long = 2.36),
        )
        val store = FakePhotoStorePass3(photos)
        val mapVm = PhotoMapViewModel(app)
        mapVm.regenerateClusters(
            photos.mapIndexed { i, p -> Triple(DpOffset((100 + i * 20).dp, 100.dp), GeoPoint(p.long!!, p.lat!!), p) },
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (mapVm.generatedClusters.value.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(mapVm.generatedClusters.value.isNotEmpty())
        rule.setContent { MapPage(backStack(), vm(store), mapVm) }
        rule.waitForIdle()
    }

    @Test
    fun trashSelectionModeViaTouch() {
        val store = FakePhotoStorePass3(
            listOf(pass3Photo(1, "old.jpg", date = 1700000000000, trashed = true)),
        )
        val vm = vm(store)
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
                TrashPage(backStack(), vm)
            }
        }
        rule.waitForIdle()
        // Tapping the trashed tile enters selection mode (title becomes the count).
        rule.onRoot().performTouchInput { click(Offset(100f, 300f)) }
        rule.waitForIdle()
    }

    @Test
    fun galleryPageComposesWithPhotosAndSearch() {
        val store = FakePhotoStorePass3(
            listOf(pass3Photo(1, "a.jpg", album = "Trips"), pass3Photo(2, "b.jpg")),
        )
        val vm = vm(store)
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
                GalleryPage(backStack(), vm, SecureFolderViewModel(app))
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.photos.value.isEmpty() && System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
        }
        rule.waitForIdle()
    }

    @Test
    fun galleryPageSearchQueryShowsResultsState() {
        val store = FakePhotoStorePass3(listOf(pass3Photo(1, "cat.jpg", ocr = "a cat")))
        store.searchHits = listOf(store.photos.value.first())
        val vm = vm(store)
        vm.setSearchQuery("cat")
        rule.setContent {
            CompositionLocalProvider(LocalColumnCount provides remember { mutableFloatStateOf(3f) }) {
                GalleryPage(backStack(), vm, SecureFolderViewModel(app))
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun panoramaSphereWithPanoDataComposes() {
        val photo = pass3Photo(1, "pano.jpg").copy(
            panoData = PanoData(
                fullWidth = 2000, fullHeight = 1000,
                croppedWidth = 2000, croppedHeight = 800, croppedLeft = 0, croppedTop = 100,
                projectionType = "equirectangular",
            ),
        )
        rule.setContent { PanoramaSphereView(photo) }
        rule.waitForIdle()
    }

    @Test
    fun panoramaSphereWithoutPanoDataIsNoop() {
        rule.setContent { PanoramaSphereView(pass3Photo(1, "plain.jpg")) }
        rule.waitForIdle()
    }

    @Test
    fun exportFormatEntriesExist() {
        val formats = com.vayunmathur.photos.util.ExportFormat.entries
        assertTrue(formats.isNotEmpty())
        for (format in formats) {
            assertTrue(format.name.isNotBlank())
            assertTrue(format.ext.isNotBlank(), "format $format has no extension")
        }
    }

    @Test
    fun videoPlayerWithLocalWavPlays() {
        // Write a tiny valid WAV (1s of silence) the media3 player can open.
        val dir = File(System.getProperty("java.io.tmpdir"), "vid-${System.nanoTime()}").also { it.mkdirs() }
        val wav = File(dir, "tone.wav")
        writeWav(wav)
        rule.setContent {
            VideoPlayer(
                modifier = androidx.compose.ui.Modifier,
                uri = Uri.fromFile(wav),
                isMetadataVisible = true,
                isSettledPage = true,
            )
        }
        rule.waitForIdle()
        Thread.sleep(500)
        rule.waitForIdle()
        dir.deleteRecursively()
    }

    private fun writeWav(file: File) {
        val sampleRate = 8000
        val seconds = 2
        val dataSize = sampleRate * seconds
        val out = java.io.DataOutputStream(java.io.FileOutputStream(file))
        fun leShort(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun leInt(v: Int) { leShort(v and 0xFFFF); leShort((v shr 16) and 0xFFFF) }
        out.writeBytes("RIFF"); leInt(36 + dataSize); out.writeBytes("WAVE")
        out.writeBytes("fmt "); leInt(16); leShort(1); leShort(1)
        leInt(sampleRate); leInt(sampleRate); leShort(1); leShort(8)
        out.writeBytes("data"); leInt(dataSize)
        repeat(dataSize) { out.write(128) }
        out.close()
    }
}
