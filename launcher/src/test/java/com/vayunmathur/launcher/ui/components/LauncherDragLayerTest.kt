package com.vayunmathur.launcher.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.LauncherItemType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Compose tests (Robolectric + createComposeRule) for [DragLayer]: empty
 * when idle, shows the dragged label while dragging, and settles (clearing
 * the drag) once a landing is committed.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDragLayerTest {

    @get:Rule val rule = createComposeRule()

    private fun payload() = DragPayload(
        itemId = 1L,
        type = LauncherItemType.APPLICATION,
        label = "Mail",
        rect = CellRect(0, 0),
        sourceBounds = Rect.Zero,
    )

    private fun show(controller: LauncherDragController) {
        rule.setContent {
            CompositionLocalProvider(LocalLauncherDrag provides controller) {
                DragLayer(controller = controller, iconScale = 1f)
            }
        }
    }

    @Test fun idleRendersNothing() {
        show(LauncherDragController())
        assertEquals(0, rule.onAllNodesWithText("Mail").fetchSemanticsNodes().size)
    }

    @Test fun draggingShowsLabel() {
        val c = LauncherDragController()
        c.start(payload(), Offset(100f, 100f))
        show(c)
        rule.onNodeWithText("Mail").assertIsDisplayed()
    }

    @Test fun landingSettlesAndClears() {
        val c = LauncherDragController()
        c.start(payload(), Offset(100f, 100f))
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(0f, 0f, 400f, 400f),
                priority = 1,
                accepts = { true },
                onDrop = { _, _ -> Rect(200f, 200f, 300f, 300f) },
                onEnter = {},
                onExit = {},
            ),
        )
        show(c)
        rule.onNodeWithText("Mail").assertIsDisplayed()
        c.drop()
        rule.waitForIdle()
        // The settle animation completes and clears the drag.
        rule.waitUntil(timeoutMillis = 5000) { !c.isDragging }
        assertFalse(c.isDragging)
    }
}
