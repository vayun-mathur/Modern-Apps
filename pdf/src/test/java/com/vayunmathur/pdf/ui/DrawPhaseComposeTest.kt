package com.vayunmathur.pdf.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.model.Quadrilateral
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafeAnnotation
import com.vayunmathur.pdf.util.SafePdfPage
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DrawPhaseComposeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val annotations = listOf(SafeAnnotation(7L, 2, 50f, 500f, 250f, 560f, 0xFFFFFF00.toInt(), "hi"))

    private fun capture() {
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(img.width > 0 && img.height > 0)
    }

    @Test fun editPreviewAllToolsDraw() {
        val tools = listOf(
            EditTool.HIGHLIGHT to MarkupKind.HIGHLIGHT, EditTool.MARKUP to MarkupKind.STRIKEOUT,
            EditTool.MARKUP to MarkupKind.UNDERLINE, EditTool.CALLOUT to MarkupKind.HIGHLIGHT,
            EditTool.REDACT to MarkupKind.HIGHLIGHT, EditTool.LINE to MarkupKind.HIGHLIGHT,
            EditTool.DRAW to MarkupKind.HIGHLIGHT, EditTool.SHAPE to MarkupKind.HIGHLIGHT,
        )
        rule.setContent {
            DynamicTheme {
                Column {
                    for ((tool, markup) in tools) {
                        SafePdfEditPreview(
                            modifier = Modifier.size(200.dp, 60.dp),
                            annotations = annotations, selected = 7L, tool = tool,
                            shape = if (tool == EditTool.SHAPE) ShapeKind.STAR_FILL else ShapeKind.RECT_OUTLINE,
                            markup = markup, color = Color.Red, ch = 800f, scale = 0.5f,
                            dragStart = Offset(20f, 20f), dragCurrent = Offset(120f, 50f),
                            moveDelta = Offset(5f, 5f),
                            inkPoints = listOf(Offset(10f, 10f), Offset(50f, 30f), Offset(90f, 20f)),
                            polyDraft = PolyDraft(0, listOf(Offset(50f, 600f), Offset(100f, 650f)), true),
                        )
                    }
                    // Shape geometry variants
                    for (shape in listOf(ShapeKind.RECT_FILL, ShapeKind.OVAL_OUTLINE, ShapeKind.TRIANGLE_FILL, ShapeKind.ROUNDRECT_FILL)) {
                        SafePdfEditPreview(
                            modifier = Modifier.size(200.dp, 60.dp),
                            annotations = emptyList(), selected = null, tool = EditTool.SHAPE,
                            shape = shape, markup = MarkupKind.HIGHLIGHT, color = Color.Blue, ch = 800f, scale = 1f,
                            dragStart = Offset(10f, 10f), dragCurrent = Offset(100f, 50f),
                            moveDelta = Offset.Zero, inkPoints = emptyList(), polyDraft = null,
                        )
                    }
                    // Empty states
                    SafePdfEditPreview(
                        modifier = Modifier.size(200.dp, 60.dp),
                        annotations = emptyList(), selected = 99L, tool = EditTool.SELECT,
                        shape = ShapeKind.RECT_OUTLINE, markup = MarkupKind.HIGHLIGHT, color = Color.Red,
                        ch = 800f, scale = 1f, dragStart = null, dragCurrent = null,
                        moveDelta = Offset.Zero, inkPoints = listOf(Offset(1f, 1f)), polyDraft = PolyDraft(0, emptyList(), false),
                    )
                }
            }
        }
        capture()
    }

    @Test fun cropOverlayAndMagnifierDraw() {
        val bmp = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        rule.setContent {
            DynamicTheme {
                Column {
                    Box(Modifier.size(300.dp, 200.dp)) {
                        CropOverlay(quadrilateral = Quadrilateral.default(), onQuadrilateralChange = {}, bitmap = bmp)
                    }
                    Box(Modifier.size(300.dp, 200.dp)) {
                        MagnifierWindow(bitmap = bmp, normalizedCorners = Quadrilateral.default().corners(), activeCorner = 2, handleScreenPos = Offset(150f, 150f), overlayWidth = 300f)
                    }
                }
            }
        }
        capture()
    }

    @Test fun pageCanvasAndTextSelectionDraw() {
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(Offset(72f, 700f), 16f, 0xFF000000.toInt(), "Hello selectable world, this is a longer line"),
            PdfPrimitive.FillPath(0xFF0000FF.toInt(), false, listOf(listOf(Offset(72f, 600f), Offset(300f, 600f), Offset(300f, 650f), Offset(72f, 650f)))),
        ))
        rule.setContent {
            DynamicTheme {
                Column {
                    SafePdfPageCanvas(page)
                }
            }
        }
        capture()
    }
}
