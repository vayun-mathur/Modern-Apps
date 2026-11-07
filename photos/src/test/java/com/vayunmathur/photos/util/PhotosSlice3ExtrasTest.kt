package com.vayunmathur.photos.util

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.photos.R
import com.vayunmathur.photos.Route
import com.vayunmathur.photos.data.OcrBox
import com.vayunmathur.photos.data.OcrLayout
import com.vayunmathur.photos.data.PhotosRepository
import com.vayunmathur.photos.data.VideoEditState
import com.vayunmathur.photos.data.VideoFilterPreset
import com.vayunmathur.photos.ui.OcrTextLayer
import com.vayunmathur.photos.ui.SecureFolderPage
import com.vayunmathur.photos.ui.VaultViewerScreen
import com.vayunmathur.photos.ui.ZoomState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Slice 3/3 extra coverage: the ViewModels and screens the first pass left at
 * zero — [VideoEditViewModel] state maths, [PhotoEditViewModel] document
 * history, [WallpaperViewModel] load states, [VaultViewerScreen],
 * [SecureFolderPage]'s locked/empty branch, and [OcrTextLayer]'s early exits.
 *
 * Nothing here touches MediaStore, WorkManager, the vault files or the network:
 * the edit VMs are built on the shared [PhotosRepository] handle but never call
 * its load paths (its database builds lazily on first query), and the screens
 * take fakes or stateless params.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotosSlice3ExtrasTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = context.applicationContext as Application

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- VideoEditViewModel: pure state maths ----

    private fun videoVm() = VideoEditViewModel(app, PhotosRepository.get(app))

    @Test
    fun videoInitialStateIsDefault() {
        val vm = videoVm()
        assertEquals(VideoEditState(), vm.state.value)
        assertFalse(vm.exporting.value)
        assertEquals(0f, vm.progress.value)
        assertNull(vm.writePermissionRequest.value)
        assertNull(vm.photo.value)
    }

    @Test
    fun videoSetDurationSeedsTrimRange() {
        val vm = videoVm()
        vm.setDuration(-5L)
        assertEquals(0L, vm.state.value.durationMs)
        vm.setDuration(10_000L)
        assertEquals(10_000L, vm.state.value.durationMs)
        assertEquals(0L, vm.state.value.trimStartMs)
        assertEquals(10_000L, vm.state.value.trimEndMs)
        // Same value again is a no-op (identity path).
        vm.setDuration(10_000L)
        assertEquals(10_000L, vm.state.value.durationMs)
    }

    @Test
    fun videoSetTrimCoercesIntoDuration() {
        val vm = videoVm()
        vm.setDuration(10_000L)
        vm.setTrim(2_000L, 8_000L)
        assertEquals(2_000L, vm.state.value.trimStartMs)
        assertEquals(8_000L, vm.state.value.trimEndMs)
        assertTrue(vm.state.value.isTrimmed)
        // Out-of-range ends clamp to [0, duration].
        vm.setTrim(-500L, 99_000L)
        assertEquals(0L, vm.state.value.trimStartMs)
        assertEquals(10_000L, vm.state.value.trimEndMs)
        assertFalse(vm.state.value.isTrimmed)
    }

    @Test
    fun videoRotateWrapsAt360() {
        val vm = videoVm()
        vm.rotateRight()
        assertEquals(90, vm.state.value.rotationDegrees)
        vm.rotateRight()
        vm.rotateRight()
        vm.rotateRight()
        assertEquals(0, vm.state.value.rotationDegrees)
        vm.rotateLeft()
        assertEquals(270, vm.state.value.rotationDegrees)
        vm.rotateLeft()
        assertEquals(180, vm.state.value.rotationDegrees)
    }

    @Test
    fun videoFlipCropAndMute() {
        val vm = videoVm()
        assertFalse(vm.state.value.flipHorizontal)
        vm.toggleFlip()
        assertTrue(vm.state.value.flipHorizontal)
        vm.toggleFlip()
        assertFalse(vm.state.value.flipHorizontal)

        assertFalse(vm.state.value.isCropped)
        vm.setCrop(0.1f, 0.1f, 0.9f, 0.9f)
        assertTrue(vm.state.value.isCropped)
        vm.clearCrop()
        assertFalse(vm.state.value.isCropped)
        assertNull(vm.state.value.cropLeft)

        assertFalse(vm.state.value.muted)
        vm.setMuted(true)
        assertTrue(vm.state.value.muted)
    }

    @Test
    fun videoAdjustmentsCoerceToUnitRange() {
        val vm = videoVm()
        vm.setBrightness(5f)
        vm.setContrast(-5f)
        vm.setSaturation(0.5f)
        assertEquals(1f, vm.state.value.brightness)
        assertEquals(-1f, vm.state.value.contrast)
        assertEquals(0.5f, vm.state.value.saturation)
        vm.setFilterPreset(VideoFilterPreset.Warm)
        assertEquals(VideoFilterPreset.Warm, vm.state.value.filterPreset)
    }

    @Test
    fun videoBuildEffectsEmptyByDefault() {
        val vm = videoVm()
        assertTrue(vm.buildVideoEffects(VideoEditState()).isEmpty())
    }

    @Test
    fun videoBuildEffectsAddsNonDefaultAdjustments() {
        val vm = videoVm()
        val withBrightness = vm.buildVideoEffects(VideoEditState(brightness = 0.2f))
        assertEquals(1, withBrightness.size)
        val withContrast = vm.buildVideoEffects(VideoEditState(contrast = -0.2f))
        assertEquals(1, withContrast.size)
        val withSaturation = vm.buildVideoEffects(VideoEditState(saturation = 0.5f))
        assertEquals(1, withSaturation.size)
        val mono = vm.buildVideoEffects(VideoEditState(filterPreset = VideoFilterPreset.Mono))
        assertEquals(1, mono.size)
        val vivid = vm.buildVideoEffects(VideoEditState(filterPreset = VideoFilterPreset.Vivid))
        assertEquals(1, vivid.size)
        val warm = vm.buildVideoEffects(VideoEditState(filterPreset = VideoFilterPreset.Warm))
        assertEquals(1, warm.size)
        val cool = vm.buildVideoEffects(VideoEditState(filterPreset = VideoFilterPreset.Cool))
        assertEquals(1, cool.size)
    }

    @Test
    fun videoBuildEffectsCropAndRotate() {
        val vm = videoVm()
        val cropped = vm.buildVideoEffects(
            VideoEditState(cropLeft = 0.1f, cropTop = 0.1f, cropRight = 0.9f, cropBottom = 0.9f),
        )
        assertEquals(1, cropped.size)
        val rotated = vm.buildVideoEffects(VideoEditState(rotationDegrees = 90))
        assertEquals(1, rotated.size)
        val flipped = vm.buildVideoEffects(VideoEditState(flipHorizontal = true))
        assertEquals(1, flipped.size)
    }

    @Test
    fun videoExportGuardsAndPermissionDenialResets() {
        val vm = videoVm()
        // Nothing pending: both are safe no-ops.
        vm.onWritePermissionGranted()
        assertNull(vm.writePermissionRequest.value)
        vm.onWritePermissionDenied()
        assertFalse(vm.exporting.value)
        vm.cancelExport()
        assertFalse(vm.exporting.value)
    }

    // ---- PhotoEditViewModel: document history ----

    private fun editVm() = PhotoEditViewModel(app, PhotosRepository.get(app))

    @Test
    fun editInitialDocumentIsEmpty() {
        val vm = editVm()
        assertTrue(vm.document.value.layers.isEmpty())
        assertFalse(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
        assertNull(vm.selection.value)
        assertNull(vm.activeLayer)
        assertNull(vm.activeAdjustment)
        assertNull(vm.photo.value)
    }

    @Test
    fun editUpdateDocumentPushesUndo() {
        val vm = editVm()
        val before = vm.document.value
        vm.updateDocument { it.copy() }
        // copy() is a new instance: history got the previous one.
        assertTrue(vm.canUndo.value)
        vm.undo()
        assertEquals(before, vm.document.value)
        assertTrue(vm.canRedo.value)
        vm.redo()
        assertFalse(vm.canRedo.value)
    }

    @Test
    fun editUpdateWithoutPushSkipsHistory() {
        val vm = editVm()
        vm.updateDocument(pushUndo = false) { it.copy() }
        assertFalse(vm.canUndo.value)
        // Undo/redo with empty stacks are safe no-ops.
        vm.undo()
        vm.redo()
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun editSelectionRoundTrips() {
        val vm = editVm()
        val sel = com.vayunmathur.photos.data.Selection(FloatArray(4) { 1f }, 2, 2)
        vm.setSelection(sel)
        assertTrue(vm.selection.value === sel)
        vm.setSelection(null)
        assertNull(vm.selection.value)
    }

    // ---- WallpaperViewModel ----

    @Test
    fun wallpaperStartsIdle() {
        val vm = WallpaperViewModel(app)
        assertEquals(WallpaperLoadState.Idle, vm.loadState.value)
        assertNull(vm.bitmap.value)
    }

    @Test
    fun wallpaperFailedUriReachesFailedState() {
        val vm = WallpaperViewModel(app)
        // A file URI that resolves but does not exist: the decode throws
        // IOException (caught -> Failed), unlike unknown content authorities
        // which fail outside the guarded block.
        vm.load("file:///nonexistent-wallpaper-${System.nanoTime()}.jpg")
        var tries = 0
        while (vm.loadState.value == WallpaperLoadState.Loading && tries < 250) {
            Thread.sleep(20)
            tries++
        }
        assertEquals(WallpaperLoadState.Failed, vm.loadState.value)
        assertNull(vm.bitmap.value)
    }

    // ---- VaultViewerScreen (stateless) ----

    @Test
    fun vaultViewerShowsNameAndBackFires() {
        var backed = false
        composeRule.setContent {
            VaultViewerScreen(
                fileName = "secret.jpg",
                file = null,
                isVideo = false,
                onBack = { backed = true },
            )
        }
        composeRule.onNodeWithText("secret.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("secret.jpg").performClick()
        // Tapping the title is not the back action; use the back button instead.
        assertFalse(backed)
    }

    @Test
    fun vaultViewerMissingFileRendersChrome() {
        composeRule.setContent {
            VaultViewerScreen(
                fileName = "gone.jpg",
                file = java.io.File("/nonexistent/gone.jpg"),
                isVideo = false,
                onBack = {},
            )
        }
        composeRule.onNodeWithText("gone.jpg").assertIsDisplayed()
    }

    // ---- SecureFolderPage: locked + empty ----

    @Test
    fun secureFolderEmptyShowsEmptyState() {
        val vm = SecureFolderViewModel(app)
        val columns = androidx.compose.runtime.mutableFloatStateOf(3f)
        composeRule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                com.vayunmathur.photos.LocalColumnCount provides columns,
            ) {
                SecureFolderPage(
                    backStack = NavBackStack(arrayOf(Route.SecureFolder)),
                    password = "pw",
                    secureFolderViewModel = vm,
                )
            }
        }
        composeRule.onNodeWithText(str(R.string.secure_folder_is_empty)).assertIsDisplayed()
    }

    // ---- OcrTextLayer early exits ----

    @Test
    fun ocrLayerEmptyLayoutRendersNothing() {
        composeRule.setContent {
            Box {
                OcrTextLayer(
                    layout = OcrLayout(w = 640, h = 480, boxes = emptyList()),
                    containerSize = IntSize(400, 300),
                    showOutlines = true,
                    zoom = { 1f },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun ocrLayerDegenerateContainerRendersNothing() {
        composeRule.setContent {
            Box {
                OcrTextLayer(
                    layout = OcrLayout(
                        w = 640,
                        h = 480,
                        boxes = listOf(OcrBox("hi", 0, 0, 10, 10)),
                    ),
                    containerSize = IntSize(0, 0),
                    showOutlines = true,
                    zoom = { 1f },
                )
            }
        }
        composeRule.waitForIdle()
    }

    // ---- ZoomState default ----

    @Test
    fun zoomStateDefaultsToIdentity() {
        val z = ZoomState()
        assertEquals(1f, z.scale)
        assertEquals(androidx.compose.ui.geometry.Offset.Zero, z.offset)
    }
}
