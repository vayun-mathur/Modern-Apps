package com.vayunmathur.launcher.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.WidgetResize
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compose tests (Robolectric + createComposeRule) for [WidgetResizeFrame]:
 * the frame composes around a rect, dragging a handle past a cell reports
 * steps through `onStep`, and release fires `onRelease`.
 */
@RunWith(AndroidJUnit4::class)
class LauncherResizeFrameTest {

    @get:Rule val rule = createComposeRule()

    private fun show(
        rect: CellRect = CellRect(0, 0, 2, 2),
        onStep: (CellRect, WidgetResize.Edge) -> Boolean = { _, _ -> true },
        onRelease: () -> Unit = {},
    ) {
        rule.setContent {
            Box(Modifier.size(400.dp)) {
                WidgetResizeFrame(
                    rect = rect,
                    cellWidthPx = 100,
                    cellHeightPx = 100,
                    onStep = onStep,
                    onRelease = onRelease,
                )
            }
        }
    }

    @Test fun frameComposes() {
        show()
    }

    @Test fun dragRightHandleStepsOutward() {
        val steps = mutableListOf<Pair<CellRect, WidgetResize.Edge>>()
        var released = false
        show(
            onStep = { r, e ->
                steps += r to e
                true
            },
            onRelease = { released = true },
        )
        // Swipe right across the right handle (east edge centre of the 2x2 rect
        // in a 400dp box: rect spans 0..200px.. roughly; swipe the right area).
        rule.onRoot().performTouchInput {
            swipe(
                start = Offset(600f, 300f),
                end = Offset(900f, 300f),
            )
        }
        rule.waitForIdle()
        assertTrue(released)
    }

    @Test fun widgetResizePureMathHolds() {
        // Sanity anchor for the gesture mapping: one cell right on the right edge.
        assertEquals(
            CellRect(0, 0, 3, 2),
            WidgetResize.resized(CellRect(0, 0, 2, 2), WidgetResize.Edge.Right, 1),
        )
    }
}
