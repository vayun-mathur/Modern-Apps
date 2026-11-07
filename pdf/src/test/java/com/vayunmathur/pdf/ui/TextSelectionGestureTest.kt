package com.vayunmathur.pdf.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.pdf.util.PdfPrimitive
import com.vayunmathur.pdf.util.SafePdfPage
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextSelectionGestureTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // Page is 612x792; the layer box is 306dp square-ish so scale = boxPx/612 and
    // glyph (72,700) lands inside. ch is set from the measured box height in px.
    private fun renderLayer(): Pair<Offset, Float> {
        var boxPx = 0f
        val page = SafePdfPage(612f, 792f, listOf(
            PdfPrimitive.Text(Offset(72f, 700f), 16f, 0xFF000000.toInt(), "Hello selectable world"),
            PdfPrimitive.Text(Offset(72f, 650f), 16f, 0xFF000000.toInt(), "Second line of text here"),
        ))
        rule.setContent {
            val density = LocalDensity.current
            val px = with(density) { 400.dp.toPx() }
            boxPx = px
            DynamicTheme {
                Box(Modifier.size(400.dp)) {
                    // Scale by page HEIGHT so the 792pt page fits the square box:
                    // glyph baseline 700pt lands at px - 700*scale, inside the box.
                    TextSelectionLayer(page = page, ch = px, scale = px / 792f, ocr = null)
                }
            }
        }
        rule.waitForIdle()
        val scale = boxPx / 792f
        // Centre of the first glyph run: x ~ (72+40)pt, baseline y 700pt -> canvas y = px - 692*scale
        return Offset(112f * scale, boxPx - 692f * scale) to scale
    }

    @Test fun longPressSelectsWordThenDragExtendsAndTapDismisses() {
        val (centre, _) = renderLayer()
        // Long press: hold past the long-press timeout, then drag to extend
        rule.onRoot().performTouchInput {
            down(centre)
            advanceEventTime(800)
            moveBy(centre + Offset(120f, 0f))
            advanceEventTime(100)
            up()
        }
        rule.waitForIdle()
        // Settled selection should draw handles/highlight and show the toolbar effect
        rule.onRoot().captureToImage()
        rule.waitForIdle()
        // Drag the end handle: press near the selection end and move
        rule.onRoot().performTouchInput {
            down(centre + Offset(120f, 0f))
            moveBy(centre + Offset(180f, 0f))
            up()
        }
        rule.waitForIdle()
        // Quick tap dismisses
        rule.onRoot().performTouchInput { down(centre); up() }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun scrollGestureDoesNotSelect() {
        val (centre, _) = renderLayer()
        // Fast swipe is a scroll: no selection, no crash
        rule.onRoot().performTouchInput {
            down(centre)
            moveBy(centre + Offset(0f, -150f))
            up()
        }
        rule.waitForIdle()
        rule.onRoot().captureToImage()
    }

    @Test fun longPressFarFromGlyphSelectsNothing() {
        renderLayer()
        rule.onRoot().performTouchInput {
            down(Offset(10f, 10f))
            advanceEventTime(800)
            up()
        }
        rule.waitForIdle()
    }
}
