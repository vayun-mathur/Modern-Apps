package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [zoomGestures] wired to real pointer input: taps at any scale, pinch, pan, pager pass-through. */
@RunWith(RobolectricTestRunner::class)
class ZoomGesturesTest {

    @get:Rule
    val rule = createComposeRule()

    private val zoom = mutableStateOf(ZoomState())
    private var taps = 0

    private fun setContent(acceptsTap: (Offset) -> Boolean = { true }) {
        rule.setContent {
            var size = IntSize.Zero
            Box(
                Modifier
                    .size(300.dp)
                    .onSizeChanged { size = it }
                    .testTag("zoom")
                    .zoomGestures(
                            zoom = zoom,
                            containerSize = { size },
                            onZoomChange = { zoom.value = it },
                            onTap = { taps++ },
                            acceptsTap = acceptsTap,
                        )
            )
        }
    }

    @Test
    fun `double tap zooms in and a second one restores fit`() {
        setContent()
        rule.onNodeWithTag("zoom").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(ZoomState.DEFAULT_DOUBLE_TAP_SCALE, zoom.value.scale)

        rule.onNodeWithTag("zoom").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(ZoomState(), zoom.value)
    }

    @Test
    fun `pinch out zooms`() {
        setContent()
        rule.onNodeWithTag("zoom").performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(120f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(120f, 0f),
            )
        }
        rule.waitForIdle()
        assertTrue(zoom.value.isZoomed, "scale was ${zoom.value.scale}")
    }

    @Test
    fun `one finger drag at fit scale leaves zoom alone`() {
        setContent()
        rule.onNodeWithTag("zoom").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(ZoomState(), zoom.value)
    }

    @Test
    fun `rejected taps neither zoom nor fire onTap`() {
        setContent(acceptsTap = { false })
        rule.onNodeWithTag("zoom").performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(ZoomState(), zoom.value)
        assertEquals(0, taps)
    }

    @Test
    fun `a tap on a zoomed image still reaches onTap`() {
        setContent()
        zoom.value = ZoomState(scale = 2f)
        rule.onNodeWithTag("zoom").performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.waitForIdle()
        assertEquals(1, taps)
        assertEquals(ZoomState(scale = 2f), zoom.value)
    }

    @Test
    fun `dragging a zoomed image pans it`() {
        setContent()
        zoom.value = ZoomState(scale = 2f)
        rule.onNodeWithTag("zoom").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(2f, zoom.value.scale)
        assertTrue(zoom.value.offset.y > 0f, "offset was ${zoom.value.offset}")
    }
}
