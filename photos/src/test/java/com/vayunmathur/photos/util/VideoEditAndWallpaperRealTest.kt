package com.vayunmathur.photos.util
import com.vayunmathur.photos.platform.workInfosOverride
import com.vayunmathur.photos.platform.WallpaperUtil
import com.vayunmathur.photos.platform.VideoEditViewModel

import android.app.Application
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.Photo
import com.vayunmathur.photos.data.TestPhotosRepository
import com.vayunmathur.photos.data.VideoData
import com.vayunmathur.photos.data.awaitPhotosCondition
import com.vayunmathur.photos.data.VideoFilterPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [VideoEditViewModel] state mutators and effect construction, plus
 * [WallpaperUtil] crop math and the real set-wallpaper path against
 * Robolectric's wallpaper manager shadow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoEditAndWallpaperRealTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private lateinit var installed: TestPhotosRepository.Installed

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workInfosOverride = { _, _ -> flowOf(emptyList()) }
        installed = TestPhotosRepository.install(app)
    }

    @Test fun videoEditMutatorsClampAndRotate() = runBlocking {
        installed.photos.upsertAll(listOf(
            Photo(name = "clip.mp4", uri = "content://media/clip", date = 1L, width = 1920, height = 1080,
                dateModified = 1L, exifSet = true, lat = null, long = null,
                videoData = VideoData(60_000L), panoData = null),
        ))
        val photo = installed.photos.getAll().first()
        val vm = VideoEditViewModel(app, installed.photos)
        vm.loadVideo(photo.id, null)
        assertTrue(awaitPhotosCondition { vm.photo.value?.id == photo.id })
        vm.setDuration(60_000L)
        vm.setTrim(5_000L, 55_000L)
        assertEquals(5_000L, vm.state.value.trimStartMs)
        assertEquals(55_000L, vm.state.value.trimEndMs)
        vm.rotateRight()
        assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateRight(); vm.rotateLeft()
        assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateLeft(); vm.rotateLeft()
        assertEquals(270, vm.state.value.rotationDegrees)
        vm.toggleFlip()
        assertTrue(vm.state.value.flipHorizontal)
        vm.setCrop(0.1f, 0.1f, 0.9f, 0.9f)
        assertEquals(0.1f, vm.state.value.cropLeft)
        vm.clearCrop()
        assertEquals(null, vm.state.value.cropLeft)
        vm.setMuted(true)
        assertTrue(vm.state.value.muted)
        vm.setBrightness(2f)
        assertEquals(1f, vm.state.value.brightness)
        vm.setContrast(-2f)
        assertEquals(-1f, vm.state.value.contrast)
        vm.setSaturation(0.5f)
        assertEquals(0.5f, vm.state.value.saturation)
        for (preset in VideoFilterPreset.entries) {
            vm.setFilterPreset(preset)
            assertEquals(preset, vm.state.value.filterPreset)
            // Every preset builds a (possibly empty) media3 effect list.
            vm.buildVideoEffects(vm.state.value)
        }
        assertFalse(vm.exporting.value)
        vm.onWritePermissionDenied()
        assertEquals(null, vm.writePermissionRequest.value)
        Unit
    }

    @Test fun wallpaperCropMathAndSet() = runBlocking {
        // Degenerate inputs fall back to the full frame.
        assertEquals(Rect(0, 0, 1, 1), WallpaperUtil.computeCropRect(0, 0, 0f, 0f, 1f, 0f, 0f, 100f, 100f, 100f, 100f))
        val rect = WallpaperUtil.computeCropRect(
            srcW = 2000, srcH = 1000, baseDisplayW = 500f, baseDisplayH = 250f,
            zoom = 2f, offsetX = 10f, offsetY = -5f,
            viewportW = 200f, viewportH = 200f, containerW = 400f, containerH = 400f,
        )
        assertTrue(rect.left in 0 until 2000 && rect.right <= 2000 && rect.width() > 0)
        assertTrue(WallpaperUtil.coverMinScale(500f, 250f, 200f, 200f) >= 0.8f)

        val source = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888)
        val result = WallpaperUtil.setWallpaper(
            context = app, source = source,
            viewport = Rect(0, 0, 64, 32),
            targetWidth = 32, targetHeight = 16,
            which = WallpaperManager.FLAG_SYSTEM,
            isScrollable = false, screenW = 32, screenH = 16,
        )
        assertEquals(WallpaperUtil.SetResult.Success, result)
        Unit
    }
}
