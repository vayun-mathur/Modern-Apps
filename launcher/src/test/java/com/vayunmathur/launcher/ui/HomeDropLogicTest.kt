package com.vayunmathur.launcher.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.platform.HomeActions
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.ui.components.CellMetrics
import com.vayunmathur.launcher.ui.components.DragPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the drop math in [HomeDropLogic]: [cellBounds],
 * [planDrop], [mergeTargetAt] and [commitDrop]. No composition needed.
 */
class HomeDropLogicTest {

    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)
    private val metrics = CellMetrics(columns = 4, rows = 4, cellWidthPx = 100, cellHeightPx = 100)
    private val pageBounds = Rect(0f, 0f, 400f, 400f)

    private fun payload(
        id: Long? = 1L,
        rect: CellRect = CellRect(2, 2),
        type: LauncherItemType = LauncherItemType.APPLICATION,
    ) = DragPayload(
        itemId = id,
        type = type,
        label = "Mail",
        rect = rect,
        origin = ContainerRef.Desktop,
        originScreen = 0,
        sourceBounds = Rect.Zero,
    )

    private fun item(id: Long, label: String, x: Int, y: Int) = WorkspaceItem(
        id = id,
        type = LauncherItemType.APPLICATION,
        label = label,
        container = ContainerRef.Desktop,
        screen = 0,
        rect = CellRect(x, y),
    )

    @Test fun cellBoundsMapsCellsToPixels() {
        assertEquals(Rect(200f, 200f, 300f, 300f), cellBounds(CellRect(2, 2), pageBounds, metrics))
    }

    @Test fun cellBoundsEmptyMetricsIsNull() {
        assertNull(cellBounds(CellRect(0, 0), pageBounds, CellMetrics.Empty))
    }

    @Test fun planDropOnEmptyCellTargetsIt() {
        val plan = planDrop(
            payload = payload(rect = CellRect(0, 0)),
            at = Offset(150f, 150f),
            pageBounds = pageBounds,
            metrics = metrics,
            spec = spec,
            placed = mapOf(1L to CellRect(0, 0)),
            page = 0,
        )
        assertNotNull(plan)
        assertEquals(CellRect(1, 1), plan!!.target)
        assertTrue(plan.displaced.isEmpty())
    }

    @Test fun planDropEmptyMetricsIsNull() {
        assertNull(
            planDrop(
                payload = payload(),
                at = Offset(150f, 150f),
                pageBounds = pageBounds,
                metrics = CellMetrics.Empty,
                spec = spec,
                placed = emptyMap(),
                page = 0,
            ),
        )
    }

    @Test fun mergeTargetNullForDrawerDrag() {
        assertNull(
            mergeTargetAt(
                payload = payload(id = null),
                at = Offset(50f, 50f),
                pageBounds = pageBounds,
                metrics = metrics,
                items = listOf(item(2, "Maps", 0, 0)),
                iconSizePx = 48f,
            ),
        )
    }

    @Test fun mergeTargetFindsCloseIcon() {
        val found = mergeTargetAt(
            payload = payload(id = 1L),
            at = Offset(55f, 55f),
            pageBounds = pageBounds,
            metrics = metrics,
            items = listOf(item(1, "Mail", 0, 0), item(2, "Maps", 0, 0)),
            iconSizePx = 144f,
        )
        assertNotNull(found)
        assertEquals(2L, found!!.id)
        assertTrue(found.progress > 0f)
    }

    @Test fun mergeTargetSkipsSelfAndWidgets() {
        assertNull(
            mergeTargetAt(
                payload = payload(id = 1L),
                at = Offset(55f, 55f),
                pageBounds = pageBounds,
                metrics = metrics,
                items = listOf(item(1, "Mail", 0, 0)),
                iconSizePx = 144f,
            ),
        )
    }

    @Test fun commitDropNullPlanReturnsNull() {
        var moved = false
        commitDrop(
            actions = object : HomeActions {
                override fun commitMove(
                    id: Long,
                    container: ContainerRef,
                    screen: Int,
                    rect: CellRect,
                    rank: Int,
                    displaced: Map<Long, CellRect>,
                ) {
                    moved = true
                }
            },
            payload = payload(),
            page = 0,
            plan = null,
            bounds = pageBounds,
            metrics = metrics,
        ).let { assertNull(it) }
        assertEquals(false, moved)
    }

    @Test fun commitDropReleaseInPlaceReturnsLandingWithoutMove() {
        var moved = false
        val landing = commitDrop(
            actions = object : HomeActions {
                override fun commitMove(
                    id: Long,
                    container: ContainerRef,
                    screen: Int,
                    rect: CellRect,
                    rank: Int,
                    displaced: Map<Long, CellRect>,
                ) {
                    moved = true
                }
            },
            payload = payload(id = 1L, rect = CellRect(2, 2)),
            page = 0,
            plan = com.vayunmathur.launcher.domain.DropPlan(CellRect(2, 2)),
            bounds = pageBounds,
            metrics = metrics,
        )
        assertEquals(Rect(200f, 200f, 300f, 300f), landing)
        assertEquals(false, moved)
    }

    @Test fun commitDropMoveCommits() {
        var move: Quad? = null
        commitDrop(
            actions = object : HomeActions {
                override fun commitMove(
                    id: Long,
                    container: ContainerRef,
                    screen: Int,
                    rect: CellRect,
                    rank: Int,
                    displaced: Map<Long, CellRect>,
                ) {
                    move = Quad(id, container, screen, rect)
                }
            },
            payload = payload(id = 1L, rect = CellRect(0, 0)),
            page = 0,
            plan = com.vayunmathur.launcher.domain.DropPlan(CellRect(3, 3)),
            bounds = pageBounds,
            metrics = metrics,
        )
        assertEquals(Quad(1L, ContainerRef.Desktop, 0, CellRect(3, 3)), move)
    }

    private data class Quad(val id: Long, val container: ContainerRef, val screen: Int, val rect: CellRect)
}
