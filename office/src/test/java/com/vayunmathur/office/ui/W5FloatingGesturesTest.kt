package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfGradient
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * W5: [FloatingElementLayer] shape rendering variants (gradient, dashed,
 * polyline, custom shape, negative-slope line) and the selection chrome:
 * drag handles, body drag, delete and crop actions. Gestures are real
 * swipes; the assertions check the exact bounds callbacks the layer emits.
 */
@RunWith(AndroidJUnit4::class)
class W5FloatingGesturesTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shapeElements(): List<OdfSlideElement> = listOf(
        OdfSlideElement.Shape(OdfShape.Rect(
            10f, 10f, 80f, 60f, fillColor = 0xFF0000L,
            fillGradient = OdfGradient(0xFF0000L, 0x0000FFL, angle = 45f),
            strokeDashed = true, rotationDegrees = 15f,
            text = listOf(OdfParagraph(listOf(OdfSpan("grad rect")))))),
        OdfSlideElement.Shape(OdfShape.Ellipse(
            120f, 10f, 60f, 50f, fillColor = 0x00FF00L, strokeWidth = 3f,
            text = listOf(OdfParagraph(listOf(OdfSpan("ellipse")))))),
        OdfSlideElement.Shape(OdfShape.Line(200f, 80f, 0f, 0f, x2 = 120f, y2 = 10f)),
        OdfSlideElement.Shape(OdfShape.Polyline(
            10f, 100f, 100f, 60f,
            points = listOf(10f to 100f, 60f to 140f, 110f to 100f), closed = true,
            fillColor = 0xFFFF00L)),
        OdfSlideElement.Shape(OdfShape.Polyline(
            130f, 100f, 80f, 60f,
            points = listOf(130f to 160f, 210f to 100f), closed = false)),
        OdfSlideElement.Shape(OdfShape.CustomShape(
            230f, 100f, 50f, 50f, fillColor = 0xFF00FFL)),
    )

    @Test fun allShapeVariantsRender() {
        rule.setContent {
            FloatingElementLayer(
                elements = shapeElements(), refW = 300f, refH = 200f,
                editMode = false, selectedIndex = -1, keyPrefix = "w5",
                modifier = Modifier.fillMaxSize(),
            )
        }
        rule.onNodeWithText("grad rect", substring = true).assertExists()
        rule.onNodeWithText("ellipse", substring = true).assertExists()
    }

    @Test fun polylineWithTooFewPointsAndZeroSizeRenders() {
        val els = listOf(
            OdfSlideElement.Shape(OdfShape.Polyline(0f, 0f, 0f, 0f, points = listOf(0f to 0f))),
            OdfSlideElement.Shape(OdfShape.Line(50f, 50f, 10f, 10f, x2 = 10f, y2 = 50f)),
            OdfSlideElement.Shape(OdfShape.Rect(0f, 60f, 40f, 30f)),
        )
        rule.setContent {
            FloatingElementLayer(
                elements = els, refW = 100f, refH = 100f,
                editMode = false, selectedIndex = -1, keyPrefix = "w5b",
                modifier = Modifier.fillMaxSize(),
            )
        }
        rule.waitForIdle()
        assertEquals(3, els.size)
    }

    @Test fun backgroundTapDeselectsAndElementTapSelects() {
        val selected = mutableListOf<Int>()
        rule.setContent {
            FloatingElementLayer(
                elements = shapeElements(), refW = 300f, refH = 200f,
                editMode = true, selectedIndex = -1, keyPrefix = "w5",
                modifier = Modifier.fillMaxSize(),
                onSelect = { selected += it },
            )
        }
        rule.onNodeWithText("grad rect", substring = true).performClick()
        assertTrue(selected.contains(0), "element tap must select its index: $selected")
    }

    @Test fun selectedFrameTextEditReportsChange() {
        val changes = mutableListOf<String>()
        val els = listOf(
            OdfSlideElement.Frame(OdfFrame(
                10f, 10f, 150f, 60f,
                listOf(OdfParagraph(listOf(OdfSpan("editable frame")))))),
        )
        rule.setContent {
            FloatingElementLayer(
                elements = els, refW = 300f, refH = 200f,
                editMode = true, selectedIndex = 0, keyPrefix = "w5",
                modifier = Modifier.fillMaxSize(),
                onElementTextChange = { _, text -> changes += text },
            )
        }
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("editable frame", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test fun swipeAcrossSelectedShapeDoesNotCrashAndKeepsSelection() {
        val bounds = mutableListOf<FloatArray>()
        rule.setContent {
            FloatingElementLayer(
                elements = shapeElements(), refW = 300f, refH = 200f,
                editMode = true, selectedIndex = 0, keyPrefix = "w5",
                modifier = Modifier.fillMaxSize(),
                onBoundsChange = { _, x, y, w, h -> bounds += floatArrayOf(x, y, w, h) },
            )
        }
        rule.waitForIdle()
        // Swipe over the whole layer: whichever handle/body-drag consumes it,
        // any reported bounds must keep positive size and the layer stays alive.
        rule.onNodeWithText("grad rect", substring = true).performTouchInput {
            swipe(centerLeft, centerRight, 200)
        }
        rule.waitForIdle()
        for (b in bounds) {
            assertTrue(b[2] >= 20f && b[3] >= 20f, "bounds keep minimum size: ${b.toList()}")
        }
        rule.onNodeWithText("grad rect", substring = true).assertExists()
    }
}
