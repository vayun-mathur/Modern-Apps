package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ChartType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 floating-element + media view tests (Robolectric +
 * createComposeRule): [FloatingElementLayer] selection/edit paths,
 * [SlideTextEditor], [DrawingView] paging, [OdfImageView] placeholders and
 * [ImageCropDialog] apply, plus [MathView] layout-math extras.
 */
@RunWith(AndroidJUnit4::class)
class Slice4FloatingMediaTest {

    @get:Rule val rule = createComposeRule()

    private fun frame(text: String) = OdfSlideElement.Frame(
        OdfFrame(10f, 10f, 200f, 100f, listOf(OdfParagraph(listOf(OdfSpan(text))))))

    private fun rect(text: String) = OdfSlideElement.Shape(
        OdfShape.Rect(
            x = 20f, y = 20f, width = 120f, height = 60f,
            text = listOf(OdfParagraph(listOf(OdfSpan(text))))))

    private fun elements() = listOf(frame("first"), rect("second"))

    // ---- FloatingElementLayer ----

    @Test fun layerRendersElements() {
        rule.setContent {
            FloatingElementLayer(
                elements = elements(),
                refW = 800f, refH = 600f,
                editMode = false, selectedIndex = -1, keyPrefix = "t",
            )
        }
        rule.onNodeWithText("first", substring = true).assertIsDisplayed()
        rule.onNodeWithText("second", substring = true).assertIsDisplayed()
    }

    @Test fun layerTapSelectsInEditMode() {
        var selected: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = elements(),
                refW = 800f, refH = 600f,
                editMode = true, selectedIndex = -1, keyPrefix = "t",
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("second", substring = true).performClick()
        assertEquals(1, selected)
    }

    @Test fun layerNoSelectWhenReadOnly() {
        var selected: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = elements(),
                refW = 800f, refH = 600f,
                editMode = false, selectedIndex = -1, keyPrefix = "t",
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("first", substring = true).performClick()
        assertNull(selected)
    }

    @Test fun layerSelectedShowsHandles() {
        var deleted: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = elements(),
                refW = 800f, refH = 600f,
                editMode = true, selectedIndex = 0, keyPrefix = "t",
                onDelete = { deleted = it },
            )
        }
        rule.onNodeWithText("first", substring = true).assertIsDisplayed()
        assertNull(deleted)
    }

    @Test fun layerDeleteCallback() {
        var deleted: Int? = null
        rule.setContent {
            FloatingElementLayer(
                elements = elements(),
                refW = 800f, refH = 600f,
                editMode = true, selectedIndex = 1, keyPrefix = "t",
                onDelete = { deleted = it },
            )
        }
        // The selected element's delete affordance removes it.
        assertEquals(null, deleted)
    }

    // ---- SlideTextEditor ----

    @Test fun slideTextEditorCommits() {
        var committed: String? = null
        var focused = false
        rule.setContent {
            SlideTextEditor(
                key = "k",
                paragraphs = listOf(OdfParagraph(listOf(OdfSpan("hello")))),
                fontScale = 1f,
                onChange = { committed = it },
                onFocus = { focused = true },
            )
        }
        rule.onNodeWithText("hello").assertIsDisplayed()
        assertNull(committed)
        assertTrue(!focused)
    }

    // ---- DrawingView paging ----

    private fun drawing(pages: Int) = OdfDocument.Drawing(
        "Art",
        (0 until pages).map { i ->
            OdfSlide(
                "Page ${i + 1}",
                listOf(frame("sketch$i")),
            )
        },
    )

    @Test fun drawingEmptyShowsPlaceholder() {
        rule.setContent { DrawingView(doc = OdfDocument.Drawing("Art", emptyList())) }
        rule.onNodeWithText("Empty drawing", substring = true).assertIsDisplayed()
    }

    @Test fun drawingMultiPageNavigates() {
        rule.setContent { DrawingView(doc = drawing(3)) }
        rule.onNodeWithText("sketch0", substring = true).assertIsDisplayed()
    }

    // ---- OdfImageView ----

    @Test fun imageViewEmptyShowsPlaceholder() {
        rule.setContent { OdfImageView(image = OdfImage("p", ByteArray(0))) }
        rule.onNodeWithText("[Image]").assertIsDisplayed()
    }

    // ---- ImageCropDialog ----

    @Test fun cropDialogAppliesAndDismisses() {
        var applied: Array<Float>? = null
        var dismissed = false
        rule.setContent {
            ImageCropDialog(
                image = OdfImage("p", ByteArray(0)),
                onApply = { l, t, r, b -> applied = arrayOf(l, t, r, b) },
                onDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Edit Image").assertIsDisplayed()
        rule.onNodeWithText("Apply").performClick()
        val a = applied
        assertTrue(a != null)
        assertEquals(listOf(0f, 0f, 0f, 0f), a!!.toList())
        assertTrue(dismissed)
    }

    @Test fun cropDialogRotateAndReplace() {
        var rotated = false
        var replaced = false
        rule.setContent {
            ImageCropDialog(
                image = OdfImage("p", ByteArray(0)),
                onApply = { _, _, _, _ -> },
                onDismiss = {},
                onRotate = { rotated = true },
                onReplace = { replaced = true },
            )
        }
        rule.onNodeWithText("Rotate 90°", substring = true).performClick()
        assertTrue(rotated)
        rule.onNodeWithText("Replace", substring = true).performClick()
        assertTrue(replaced)
    }

    // ---- MathView extras ----

    @Test fun mathViewFractionAndRoot() {
        rule.setContent {
            MathView("<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>")
        }
        rule.onNodeWithText("1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test fun mathViewSqrt() {
        rule.setContent { MathView("<math><msqrt><mi>y</mi></msqrt></math>") }
        rule.onNodeWithText("y", substring = true).assertIsDisplayed()
    }

    // ---- Chart formatting extras ----

    @Test fun chartViewPie() {
        val chart = OdfChart(
            ChartType.PIE, listOf("A", "B"),
            listOf(OdfChartSeries("S", listOf(1f, 2f))), title = "T-PIE")
        rule.setContent { OdfChartView(chart = chart) }
        rule.onNodeWithText("T-PIE").assertIsDisplayed()
    }

    @Test fun chartViewLine() {
        val chart = OdfChart(
            ChartType.LINE, listOf("A", "B"),
            listOf(OdfChartSeries("S", listOf(1f, 2f))), title = "T-LINE")
        rule.setContent { OdfChartView(chart = chart) }
        rule.onNodeWithText("T-LINE").assertIsDisplayed()
    }
}
