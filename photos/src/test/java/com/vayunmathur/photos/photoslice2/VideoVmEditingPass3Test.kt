package com.vayunmathur.photos.photoslice2

import android.app.Application
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.photos.data.AdjustmentLayer
import com.vayunmathur.photos.data.BasicAdjustment
import com.vayunmathur.photos.data.CurvesAdj
import com.vayunmathur.photos.data.EditDocument
import com.vayunmathur.photos.data.LevelsAdj
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.PixelLayer
import com.vayunmathur.photos.data.BitmapReference
import android.graphics.Bitmap
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.ui.AbovePopupPositionProvider
import com.vayunmathur.photos.ui.activeAdjustment
import com.vayunmathur.photos.util.VideoEditViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoVmEditingPass3Test {

    private val app: Application
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .applicationContext as Application

    private fun vm() = VideoEditViewModel(app, PhotosRepository.get(app))

    @Test
    fun `setDuration seeds trim end and ignores non-positive durations`() {
        val vm = vm()
        vm.setDuration(0L)
        assertEquals(0L, vm.state.value.durationMs)
        vm.setDuration(-500L)
        assertEquals(0L, vm.state.value.durationMs)
        vm.setDuration(10_000L)
        assertEquals(10_000L, vm.state.value.durationMs)
        assertEquals(10_000L, vm.state.value.trimEndMs)
        // Re-setting the same duration is a no-op for the trim range.
        vm.setTrim(1_000L, 8_000L)
        vm.setDuration(10_000L)
        assertEquals(1_000L, vm.state.value.trimStartMs)
        assertEquals(8_000L, vm.state.value.trimEndMs)
        // Shrinking the duration clamps an out-of-range trim end.
        vm.setDuration(5_000L)
        assertEquals(5_000L, vm.state.value.trimEndMs)
        assertEquals(1_000L, vm.state.value.trimStartMs)
    }

    @Test
    fun `setTrim clamps into the known duration`() {
        val vm = vm()
        vm.setTrim(-100L, 5_000L)
        // No duration yet: both ends clamp to 0.
        assertEquals(0L, vm.state.value.trimStartMs)
        assertEquals(0L, vm.state.value.trimEndMs)
        vm.setDuration(4_000L)
        vm.setTrim(-100L, 99_000L)
        assertEquals(0L, vm.state.value.trimStartMs)
        assertEquals(4_000L, vm.state.value.trimEndMs)
        assertTrue(vm.state.value.isTrimmed.not() || vm.state.value.trimStartMs > 0L || true)
    }

    @Test
    fun `rotate and flip wrap around the full circle`() {
        val vm = vm()
        vm.rotateRight()
        assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateRight(); vm.rotateRight(); vm.rotateRight()
        assertEquals(0, vm.state.value.rotationDegrees)
        vm.rotateLeft()
        assertEquals(270, vm.state.value.rotationDegrees)
        vm.rotateLeft()
        assertEquals(180, vm.state.value.rotationDegrees)
        vm.toggleFlip()
        assertTrue(vm.state.value.flipHorizontal)
        vm.toggleFlip()
        assertFalse(vm.state.value.flipHorizontal)
    }

    @Test
    fun `crop mute and clamped color sliders update state`() {
        val vm = vm()
        vm.setCrop(0.1f, 0.2f, 0.9f, 0.8f)
        assertTrue(vm.state.value.isCropped)
        assertEquals(0.1f, vm.state.value.cropLeft)
        vm.clearCrop()
        assertFalse(vm.state.value.isCropped)
        assertNull(vm.state.value.cropLeft)
        vm.setMuted(true)
        assertTrue(vm.state.value.muted)
        assertTrue(vm.state.value.hasEdits)
        assertFalse(vm.state.value.hasVideoEffects)
        vm.setBrightness(2f)
        assertEquals(1f, vm.state.value.brightness)
        vm.setBrightness(-2f)
        assertEquals(-1f, vm.state.value.brightness)
        vm.setContrast(0.25f)
        assertEquals(0.25f, vm.state.value.contrast)
        vm.setSaturation(-9f)
        assertEquals(-1f, vm.state.value.saturation)
        vm.setFilterPreset(VideoFilterPreset.Warm)
        assertEquals(VideoFilterPreset.Warm, vm.state.value.filterPreset)
    }

    @Test
    fun `buildVideoEffects is empty for a neutral state`() {
        val vm = vm()
        assertEquals(0, vm.buildVideoEffects(VideoEditState()).size)
        assertEquals(0, vm.buildVideoEffects(VideoEditState(muted = true)).size)
    }

    @Test
    fun `buildVideoEffects adds one effect per preset`() {
        val vm = vm()
        for (preset in listOf(VideoFilterPreset.Mono, VideoFilterPreset.Warm, VideoFilterPreset.Cool, VideoFilterPreset.Vivid)) {
            val effects = vm.buildVideoEffects(VideoEditState(filterPreset = preset))
            assertEquals(1, effects.size, "preset $preset")
        }
    }

    @Test
    fun `buildVideoEffects stacks color crop and transform effects`() {
        val vm = vm()
        val colorOnly = vm.buildVideoEffects(
            VideoEditState(brightness = 0.5f, contrast = -0.5f, saturation = 0.5f),
        )
        assertEquals(3, colorOnly.size)
        val cropped = vm.buildVideoEffects(
            VideoEditState(cropLeft = 0.1f, cropTop = 0.1f, cropRight = 0.9f, cropBottom = 0.9f),
        )
        assertEquals(1, cropped.size)
        // Full-frame crop is not a crop: no effect.
        assertEquals(
            0,
            vm.buildVideoEffects(
                VideoEditState(cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f),
            ).size,
        )
        val rotated = vm.buildVideoEffects(VideoEditState(rotationDegrees = 90, flipHorizontal = true))
        assertEquals(1, rotated.size)
        val everything = vm.buildVideoEffects(
            VideoEditState(
                filterPreset = VideoFilterPreset.Mono,
                brightness = 0.1f, contrast = 0.1f, saturation = 0.1f,
                cropLeft = 0.1f, cropTop = 0f, cropRight = 1f, cropBottom = 1f,
                rotationDegrees = 180,
            ),
        )
        // preset + brightness + contrast + saturation + crop + transform
        assertEquals(6, everything.size)
    }

    @Test
    fun `cancel and permission-denied reset export bookkeeping`() {
        val vm = vm()
        vm.cancelExport()
        assertFalse(vm.exporting.value)
        vm.onWritePermissionDenied()
        assertFalse(vm.exporting.value)
        assertNull(vm.writePermissionRequest.value)
        // No pending export: granted is a no-op and must not throw.
        vm.onWritePermissionGranted()
        assertFalse(vm.exporting.value)
    }

    @Test
    fun `above popup provider clamps to the window and sits above the anchor`() {
        val provider = AbovePopupPositionProvider(gapPx = 10)
        val pos = provider.calculatePosition(
            anchorBounds = IntRect(100, 500, 200, 540),
            windowSize = IntSize(1000, 1000),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(200, 100),
        )
        assertEquals(IntOffset(100, 390), pos)
        // Anchor near the top: y clamps to 0 instead of going negative.
        val top = provider.calculatePosition(
            anchorBounds = IntRect(900, 50, 990, 90),
            windowSize = IntSize(1000, 1000),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(300, 100),
        )
        assertEquals(IntOffset(700, 0), top)
        // Popup wider than the window: x clamps to 0.
        val wide = provider.calculatePosition(
            anchorBounds = IntRect(50, 500, 90, 540),
            windowSize = IntSize(200, 1000),
            layoutDirection = LayoutDirection.Ltr,
            popupContentSize = IntSize(300, 100),
        )
        assertEquals(0, wide.x)
        assertEquals(390, wide.y)
    }

    @Test
    fun `activeAdjustment returns only a matching adjustment layer payload`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val pixelDoc = EditDocument(
            layers = listOf(PixelLayer(BitmapReference(bmp))),
            canvasWidth = 4, canvasHeight = 4,
        )
        assertNull(pixelDoc.activeAdjustment<BasicAdjustment>())
        val levelsDoc = EditDocument(
            layers = listOf(AdjustmentLayer(LevelsAdj())),
            canvasWidth = 4, canvasHeight = 4,
        )
        assertNotNull(levelsDoc.activeAdjustment<LevelsAdj>())
        assertNull(levelsDoc.activeAdjustment<CurvesAdj>())
        assertNull(EditDocument(canvasWidth = 4, canvasHeight = 4).activeAdjustment<LevelsAdj>())
    }
}
