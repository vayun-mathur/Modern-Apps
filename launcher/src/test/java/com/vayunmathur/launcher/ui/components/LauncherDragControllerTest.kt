package com.vayunmathur.launcher.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.LauncherItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure state-machine tests for [LauncherDragController]: start/move/drop/
 * cancel, target registration and hit-testing, source lookup, and the
 * settle lifecycle. No composition needed.
 */
class LauncherDragControllerTest {

    private fun payload(id: Long? = 1L) = DragPayload(
        itemId = id,
        type = LauncherItemType.APPLICATION,
        label = "Mail",
        rect = CellRect(0, 0),
        sourceBounds = Rect(0f, 0f, 100f, 100f),
    )

    @Test fun idleHasNoPayloadOrTarget() {
        val c = LauncherDragController()
        assertFalse(c.isDragging)
        assertFalse(c.isSettling)
        assertNull(c.payload)
    }

    @Test fun startTracksPayloadAndPosition() {
        val c = LauncherDragController()
        c.start(payload(), Offset(10f, 20f))
        assertTrue(c.isDragging)
        assertEquals(1L, c.payload?.itemId)
        assertEquals(Offset(10f, 20f), c.position)
    }

    @Test fun moveWithoutPayloadIsNoOp() {
        val c = LauncherDragController()
        c.move(Offset(50f, 50f))
        assertFalse(c.isDragging)
    }

    @Test fun dropWithNoTargetReturnsToSourceBounds() {
        val c = LauncherDragController()
        c.start(payload(), Offset.Zero)
        assertFalse(c.drop())
        // Refused with a source rect: lands back where it came from (settling).
        assertTrue(c.isSettling)
        assertEquals(Rect(0f, 0f, 100f, 100f), c.landing)
        c.settled()
        assertFalse(c.isDragging)
        assertFalse(c.isSettling)
    }

    @Test fun dropWithNoSourceBoundsClearsImmediately() {
        val c = LauncherDragController()
        c.start(payload().copy(sourceBounds = Rect.Zero), Offset.Zero)
        assertFalse(c.drop())
        assertFalse(c.isDragging)
        assertNull(c.landing)
    }

    @Test fun dropOnTargetCommitsAndSettles() {
        val c = LauncherDragController()
        var dropped: DragPayload? = null
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 1,
                accepts = { true },
                onDrop = { p, _ ->
                    dropped = p
                    Rect(0f, 0f, 50f, 50f)
                },
                onEnter = {},
                onExit = {},
            ),
        )
        c.start(payload(), Offset(10f, 10f))
        assertTrue(c.drop())
        assertEquals(1L, dropped?.itemId)
        assertEquals(Rect(0f, 0f, 50f, 50f), c.landing)
    }

    @Test fun unregisterTargetStopsHits() {
        val c = LauncherDragController()
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 1,
                accepts = { true },
                onDrop = { _, _ -> Rect.Zero },
                onEnter = {},
                onExit = {},
            ),
        )
        c.unregisterTarget("t")
        c.start(payload(), Offset(10f, 10f))
        assertFalse(c.drop())
    }

    @Test fun sourceAtFindsRegisteredSource() {
        val c = LauncherDragController()
        val p = payload()
        c.registerSource("s", Rect(0f, 0f, 100f, 100f)) { p }
        assertEquals(p, c.sourceAt(Offset(50f, 50f)))
        assertNull(c.sourceAt(Offset(500f, 500f)))
        c.unregisterSource("s")
        assertNull(c.sourceAt(Offset(50f, 50f)))
    }

    @Test fun cancelClearsDrag() {
        val c = LauncherDragController()
        var exited = false
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 1,
                accepts = { true },
                onDrop = { _, _ -> null },
                onEnter = {},
                onExit = { exited = true },
            ),
        )
        c.start(payload(), Offset(10f, 10f))
        c.cancel()
        assertFalse(c.isDragging)
        assertTrue(exited)
    }

    @Test fun dropOutsideTargetRefuses() {
        val c = LauncherDragController()
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(500f, 500f, 600f, 600f),
                priority = 1,
                accepts = { true },
                onDrop = { _, _ -> Rect.Zero },
                onEnter = {},
                onExit = {},
            ),
        )
        c.start(payload(), Offset(10f, 10f))
        assertFalse(c.drop())
    }

    @Test fun acceptsFalseTargetIsIgnored() {
        val c = LauncherDragController()
        var called = false
        c.registerTarget(
            "t",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 10,
                accepts = { false },
                onDrop = { _, _ ->
                    called = true
                    Rect.Zero
                },
                onEnter = {},
                onExit = {},
            ),
        )
        c.start(payload().copy(type = LauncherItemType.APPWIDGET), Offset(10f, 10f))
        assertFalse(c.drop())
        assertFalse(called)
    }

    @Test fun highestPriorityTargetWins() {
        val c = LauncherDragController()
        val order = mutableListOf<String>()
        c.registerTarget(
            "low",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 1,
                accepts = { true },
                onDrop = { _, _ ->
                    order += "low"
                    Rect.Zero
                },
                onEnter = {},
                onExit = {},
            ),
        )
        c.registerTarget(
            "high",
            DropTarget(
                bounds = Rect(0f, 0f, 200f, 200f),
                priority = 5,
                accepts = { true },
                onDrop = { _, _ ->
                    order += "high"
                    Rect.Zero
                },
                onEnter = {},
                onExit = {},
            ),
        )
        c.start(payload(), Offset(10f, 10f))
        assertTrue(c.drop())
        assertEquals(listOf("high"), order)
    }
}
