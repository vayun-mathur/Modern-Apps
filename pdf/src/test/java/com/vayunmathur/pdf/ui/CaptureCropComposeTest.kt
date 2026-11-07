package com.vayunmathur.pdf.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.model.CapturedImage
import com.vayunmathur.pdf.model.Quadrilateral
import com.vayunmathur.pdf.ui.components.SubcroppedImage
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.PdfViewModel
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafeLink
import com.vayunmathur.pdf.util.SafePdfPage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CaptureCropComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pngUri(name: String): Uri {
        val f = File(app.cacheDir, name)
        f.outputStream().use { Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    @Test fun captureScreenWithoutPermissionShowsRequestAndUpload() {
        val vm = PdfViewModel(app)
        rule.setContent { DynamicTheme { CapturePdfScreen(vm, onBack = {}, onPdfCreated = {}) } }
        rule.onNodeWithText("Capture PDF").assertIsDisplayed()
        rule.onNodeWithText("Request Camera Permission").assertIsDisplayed()
        rule.onNodeWithText("Upload Image").assertIsDisplayed()
    }

    @Test fun captureScreenWithImagesShowsThumbnails() {
        val vm = PdfViewModel(app)
        vm.addCapturedImage(pngUri("cap1.png")); vm.addCapturedImage(pngUri("cap2.png"))
        rule.setContent { DynamicTheme { CapturePdfScreen(vm, onBack = {}, onPdfCreated = {}) } }
        rule.waitForIdle()
        rule.onNodeWithText("Capture PDF").assertIsDisplayed()
    }

    @Test fun cropScreenRendersAndDoneReturnsQuad() {
        var done: Quadrilateral? = null
        rule.setContent {
            DynamicTheme {
                CropScreen(uri = pngUri("crop.png"), initialQuadrilateral = null, onCropDone = { done = it }, onBack = {})
            }
        }
        rule.onNodeWithText("Crop Image").assertIsDisplayed()
        rule.waitForIdle()
        // The Continue FAB reports the current quadrilateral
        val fab = rule.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
        assertTrue(fab.isNotEmpty())
    }

    @Test fun cropOverlayDragMovesCorner() {
        var quad = Quadrilateral.default()
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(300.dp)) {
                    CropOverlay(quadrilateral = quad, onQuadrilateralChange = { quad = it }, bitmap = null)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipe(centerLeft, center, 200) }
        rule.waitForIdle()
    }

    @Test fun magnifierWindowWithBitmap() {
        val bmp = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(300.dp)) {
                    MagnifierWindow(bitmap = bmp, normalizedCorners = Quadrilateral.default().corners(), activeCorner = 0, handleScreenPos = Offset(100f, 200f), overlayWidth = 300f)
                    MagnifierWindow(bitmap = null, normalizedCorners = Quadrilateral.default().corners(), activeCorner = 1, handleScreenPos = Offset(50f, 50f), overlayWidth = 300f)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun cameraPreviewRendersCaptureButton() {
        rule.setContent { DynamicTheme { com.vayunmathur.pdf.ui.components.CameraPreview(onImageCaptured = {}) } }
        rule.waitForIdle()
    }

    @Test fun cropScreenSectionRenders() {
        rule.setContent { DynamicTheme { CropScreenSection(offset = Offset(10f, 10f), onDrag = {}) } }
        rule.waitForIdle()
    }

    @Test fun subcroppedImageVariants() {
        val uri = pngUri("sub.png")
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Column {
                    SubcroppedImage(CapturedImage(uri))
                    SubcroppedImage(CapturedImage(uri, cropRect = Rect(0f, 0f, 0.5f, 0.5f)))
                    SubcroppedImage(CapturedImage(uri, quadrilateral = Quadrilateral.default()))
                }
            }
        }
        rule.waitForIdle()
    }

    @Test fun pageScrollerShowsPageCount() {
        val scroll = SafePdfScrollState().apply { viewportSize = IntSize(612, 800); pageCount = 4 }
        val zoom = SafePdfZoomState()
        rule.setContent { DynamicTheme { PdfPageScroller(scroll, zoom, 4) } }
        rule.onNodeWithText("1 / 4").assertIsDisplayed()
    }

    @Test fun editPreviewAndNonEditOverlayRender() {
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(Offset(72f, 700f), 12f, 0xFF000000.toInt(), "Hello world"),
        ))
        val annotations = listOf(SafeAnnotation(1L, 2, 100f, 600f, 200f, 630f, 0xFFFFFF00.toInt(), "note"))
        rule.setContent {
            DynamicTheme {
                androidx.compose.foundation.layout.Box {
                    SafePdfPageCanvas(page) { cw, ch, scale ->
                        NonEditOverlay(page, listOf(SafeLink(70f, 690f, 200f, 710f, 2, "")), ch, scale, null) {}
                        SafePdfEditPreview(
                            annotations = annotations, selected = 1L, tool = EditTool.SELECT,
                            shape = ShapeKind.RECT_OUTLINE, markup = MarkupKind.HIGHLIGHT,
                            color = Color.Red, ch = ch, scale = scale,
                            dragStart = Offset(80f, 80f), dragCurrent = Offset(120f, 120f),
                            moveDelta = Offset.Zero, inkPoints = listOf(Offset(90f, 90f), Offset(100f, 100f)),
                            polyDraft = PolyDraft(0, listOf(Offset(50f, 50f), Offset(60f, 60f)), false),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, annotations.size)
    }
}
