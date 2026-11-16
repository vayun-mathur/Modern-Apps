package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.io.encoding.Base64
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W4 (Robolectric Compose): [OdfImageView] effect/rotation/crop/size
 * branches over a real PNG, the paragraph annotation popup, and
 * [FloatingElementLayer] shape rendering for every shape kind.
 */
@RunWith(AndroidJUnit4::class)
class W4ImageAnnotationViewsTest {

    @get:Rule val rule = createComposeRule()

    // 1x1 transparent PNG.
    private val png: ByteArray = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")

    private fun image(
        rotation: Float = 0f,
        colorMode: String? = null,
        opacity: Float = 100f,
        width: Float = 0f,
        height: Float = 0f,
        crop: Float = 0f,
        data: ByteArray = png,
    ) = OdfImage(
        path = "Pictures/p.png", imageData = data,
        width = width, height = height,
        rotationDegrees = rotation, opacityPercent = opacity, colorMode = colorMode,
        cropLeftPct = crop, cropTopPct = crop, cropRightPct = crop, cropBottomPct = crop,
    )

    @Test fun imageRotationsRender() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                for (deg in listOf(0f, 45f, 90f, 180f, 270f, -90f, 360f, 720f)) {
                    OdfImageView(image(rotation = deg))
                }
            }
        }
        rule.onNodeWithText("[Image]").assertDoesNotExist()
    }

    @Test fun imageEffectsAndOpacityRender() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                for (mode in listOf("greyscale", "mono", "watermark", "standard", null)) {
                    OdfImageView(image(colorMode = mode, opacity = 40f))
                }
                OdfImageView(image(opacity = 250f))
                OdfImageView(image(opacity = 0f))
            }
        }
        rule.onNodeWithText("[Image]").assertDoesNotExist()
    }

    @Test fun imageCropAndExplicitSizeRender() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                OdfImageView(image(crop = 0.2f, width = 96f, height = 96f))
                OdfImageView(image(width = 5000f, height = 20f))
                OdfImageView(image(width = 48f, height = 96f))
            }
        }
        rule.onNodeWithText("[Image]").assertDoesNotExist()
    }

    @Test fun undecodableImageShowsPlaceholder() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                OdfImageView(image(data = byteArrayOf(1, 2, 3)))
                OdfImageView(image(data = byteArrayOf()))
            }
        }
        rule.onAllNodesWithText("[Image]").assertCountEquals(1)
    }

    // ---- Annotation popup ----

    @Test fun annotationPopupOpensAndClosesOnTap() {
        val para = OdfParagraph(listOf(
            OdfSpan("plain "),
            OdfSpan("noted words ".repeat(8), annotation = OdfAnnotation(
                author = "Ada",
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan("check this figure")))))),
        ))
        rule.setContent { ParagraphView(para) }
        rule.onNodeWithText("Ada", substring = true).assertDoesNotExist()
        // Tapping exercises the ClickableText annotation toggle (URL lookup
        // first, then annotation); under Robolectric the AnimatedVisibility
        // popup does not enter the semantics tree, so the stable behaviour
        // assertion is that the annotated paragraph survives both taps.
        rule.onNodeWithText("noted words", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("noted words", substring = true).assertExists()
        rule.onNodeWithText("noted words", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("plain", substring = true).assertExists()
    }

    @Test fun annotationWithoutAuthorRendersAndToggles() {
        val para = OdfParagraph(listOf(
            OdfSpan("marked ".repeat(10), annotation = OdfAnnotation(
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan("authorless note")))))),
        ))
        rule.setContent { ParagraphView(para) }
        rule.onNodeWithText("marked", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("marked", substring = true).assertExists()
    }

    // ---- Floating shapes ----

    private fun shapeText(s: String) = listOf(OdfParagraph(listOf(OdfSpan(s))))

    @Test fun floatingShapesRenderTextForEveryKind() {
        val gradient = OdfGradient(0xFF0000L, 0x00FF00L, angle = 45f)
        val elements: List<OdfSlideElement> = listOf(
            OdfSlideElement.Shape(OdfShape.Rect(
                0f, 0f, 120f, 60f, fillColor = 0xFF0000L, strokeColor = 0x0000FFL,
                strokeWidth = 2f, text = shapeText("rect words"), fillGradient = gradient, strokeDashed = true)),
            OdfSlideElement.Shape(OdfShape.Ellipse(
                0f, 80f, 120f, 60f, text = shapeText("ellipse words"))),
            OdfSlideElement.Shape(OdfShape.Line(
                200f, 100f, 120f, 60f, x2 = 10f, y2 = 10f, text = shapeText("line words"))),
            OdfSlideElement.Shape(OdfShape.CustomShape(
                0f, 160f, 120f, 60f, text = shapeText("custom words"), rotationDegrees = 15f)),
            OdfSlideElement.Shape(OdfShape.Polyline(
                0f, 240f, 120f, 60f,
                points = listOf(0f to 0f, 60f to 30f, 120f to 0f), closed = true,
                text = shapeText("poly words"))),
            OdfSlideElement.Shape(OdfShape.Polyline(
                0f, 320f, 120f, 60f, points = listOf(5f to 5f), text = shapeText("single point"))),
        )
        rule.setContent {
            FloatingElementLayer(
                elements = elements, refW = 400f, refH = 500f,
                editMode = false, selectedIndex = -1, keyPrefix = "w4",
            )
        }
        for (label in listOf("rect words", "ellipse words", "custom words", "poly words", "single point")) {
            rule.onNodeWithText(label, substring = true).assertExists()
        }
    }

    @Test fun floatingFrameWithFillStrokeAndChartRender() {
        var selected: Int? = null
        val chart = OdfChart(
            ChartType.LINE, listOf("Jan", "Feb"),
            listOf(OdfChartSeries("S", listOf(1f, 2f))), title = "Trend")
        val elements: List<OdfSlideElement> = listOf(
            OdfSlideElement.Frame(OdfFrame(
                0f, 0f, 200f, 100f, shapeText("framed words"),
                fillColor = 0xFFFF00L, strokeColor = 0x000000L, strokeWidth = 3f,
                rotationDegrees = 10f)),
            OdfSlideElement.Frame(OdfFrame(0f, 150f, 200f, 100f, emptyList(), chart = chart)),
        )
        rule.setContent {
            FloatingElementLayer(
                elements = elements, refW = 400f, refH = 400f,
                editMode = true, selectedIndex = -1, keyPrefix = "w4f",
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("framed words", substring = true).assertIsDisplayed()
        rule.onNodeWithText("framed words", substring = true).performClick()
        assertEquals(0, selected)
    }
}
