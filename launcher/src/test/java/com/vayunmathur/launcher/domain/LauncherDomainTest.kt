package com.vayunmathur.launcher.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CellOccupancyTest {

    @Test
    fun `a marked rect is no longer free`() {
        val occupancy = CellOccupancy(4, 4)
        occupancy.mark(CellRect(1, 1, 2, 2))
        assertFalse(occupancy.isFree(CellRect(1, 1)))
        assertFalse(occupancy.isFree(CellRect(2, 2)))
        assertTrue(occupancy.isFree(CellRect(0, 0)))
        assertTrue(occupancy.isFree(CellRect(3, 3)))
    }

    @Test
    fun `a rect straddling the edge does not fit`() {
        val occupancy = CellOccupancy(4, 4)
        // The grid has no cell at x=4, so a 2-wide rect starting at x=3 hangs off it.
        // Treating out-of-bounds as occupied is what saves every caller a bounds check.
        assertFalse(occupancy.isFree(CellRect(3, 0, 2, 1)))
        assertFalse(occupancy.isFree(CellRect(0, -1)))
        assertTrue(occupancy.isFree(CellRect(2, 0, 2, 1)))
    }

    @Test
    fun `unmarking frees the cells again`() {
        val occupancy = CellOccupancy(3, 3)
        val rect = CellRect(0, 0, 3, 1)
        occupancy.mark(rect)
        assertEquals(6, occupancy.freeCellCount)
        occupancy.mark(rect, occupied = false)
        assertEquals(9, occupancy.freeCellCount)
    }
}

class GridPlacerTest {

    @Test
    fun `the preferred cell wins when it is free`() {
        val occupancy = CellOccupancy(4, 4)
        assertEquals(CellRect(2, 3), GridPlacer.findNearestVacant(occupancy, CellRect(2, 3)))
    }

    @Test
    fun `an occupied preference falls to the nearest hole`() {
        val occupancy = CellOccupancy(4, 4)
        occupancy.mark(CellRect(2, 2))
        val found = GridPlacer.findNearestVacant(occupancy, CellRect(2, 2))
        // Adjacent, not somewhere across the page.
        assertEquals(1, found!!.let { kotlin.math.abs(it.cellX - 2) + kotlin.math.abs(it.cellY - 2) })
    }

    @Test
    fun `a full page has nowhere to put anything`() {
        val occupancy = CellOccupancy(2, 2)
        occupancy.mark(CellRect(0, 0, 2, 2))
        assertNull(GridPlacer.findNearestVacant(occupancy, CellRect(0, 0)))
        assertNull(GridPlacer.findFirstVacant(occupancy, 1, 1))
    }

    @Test
    fun `a wide span skips holes too narrow for it`() {
        val occupancy = CellOccupancy(4, 2)
        // Leaves single-cell gaps at (1,0) and (3,0), neither of which can take a 2-wide item.
        occupancy.mark(CellRect(0, 0))
        occupancy.mark(CellRect(2, 0))
        assertEquals(CellRect(0, 1, 2, 1), GridPlacer.findFirstVacant(occupancy, 2, 1))
    }
}

class AutoPlacerTest {

    private val spec = GridSpec(columns = 4, rows = 5, hotseatSlots = 4)

    @Test
    fun `the first item lands top left`() {
        assertEquals(PagedRect(0, CellRect(0, 0)), AutoPlacer.place(spec, emptyList(), CellRect(0, 0)))
    }

    @Test
    fun `a full page pushes the next item onto a new one`() {
        val full = (0 until spec.rows).flatMap { y ->
            (0 until spec.columns).map { x -> PagedRect(0, CellRect(x, y)) }
        }
        assertEquals(PagedRect(1, CellRect(0, 0)), AutoPlacer.place(spec, full, CellRect(0, 0)))
    }

    @Test
    fun `an oversized span is clamped rather than rejected`() {
        // Providers report min/max spans unreliably, so a 9x9 request has to become
        // something placeable instead of throwing.
        val placed = AutoPlacer.place(spec, emptyList(), CellRect(0, 0, 9, 9))
        assertEquals(4, placed.rect.spanX)
        assertEquals(5, placed.rect.spanY)
    }

    @Test
    fun `placeAll sees each placement while choosing the next`() {
        val placed = AutoPlacer.placeAll(spec, emptyList(), List(3) { CellRect(0, 0) })
        assertEquals(3, placed.distinctBy { it.screen to it.rect }.size)
    }

    @Test
    fun `growing the grid pulls items forward onto fewer pages`() {
        val wide = GridSpec(columns = 4, rows = 4)
        val items = listOf(
            GridItem(1, 0, CellRect(0, 0)),
            GridItem(2, 0, CellRect(1, 0)),
            GridItem(3, 0, CellRect(0, 1)),
            GridItem(4, 0, CellRect(1, 1)),
            GridItem(5, 1, CellRect(0, 0)),
        )
        val regridded = AutoPlacer.regrid(items, wide)
        assertTrue(regridded.all { it.screen == 0 })
        // Reading order survives, so the layout is still recognisable afterwards.
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            regridded.sortedWith(compareBy({ it.rect.cellY }, { it.rect.cellX })).map { it.id },
        )
    }

    @Test
    fun `shrinking the grid spills onto new pages instead of dropping items`() {
        val narrow = GridSpec(columns = 2, rows = 2)
        val items = (0 until 16).map { index ->
            GridItem(index.toLong(), 0, CellRect(index % 4, index / 4))
        }
        val regridded = AutoPlacer.regrid(items, narrow)
        assertEquals(16, regridded.size)
        assertEquals(items.map { it.id }.toSet(), regridded.map { it.id }.toSet())
        // 16 items into pages of 4 cells means four pages, and none may overlap.
        assertEquals(4, regridded.map { it.screen }.distinct().size)
        regridded.groupBy { it.screen }.forEach { (_, onPage) ->
            val occupancy = CellOccupancy(narrow)
            onPage.forEach { item ->
                assertTrue(occupancy.isFree(item.rect), "overlap at ${item.rect}")
                occupancy.mark(item.rect)
            }
        }
    }

    @Test
    fun `a widget too wide for the new grid is clamped and kept`() {
        val items = listOf(GridItem(1, 0, CellRect(0, 0, 4, 2)))
        val regridded = AutoPlacer.regrid(items, GridSpec(columns = 3, rows = 3))
        assertEquals(1, regridded.size)
        assertEquals(3, regridded.single().rect.spanX)
    }
}

class FolderRulesTest {

    @Test
    fun `apps and shortcuts merge, widgets and folders do not`() {
        assertTrue(FolderRules.canMerge(LauncherItemType.APPLICATION, LauncherItemType.APPLICATION))
        assertTrue(FolderRules.canMerge(LauncherItemType.DEEP_SHORTCUT, LauncherItemType.FOLDER))
        assertTrue(FolderRules.canMerge(LauncherItemType.APPLICATION, LauncherItemType.FOLDER))
        // A widget has a span; a folder child has no cell to give one.
        assertFalse(FolderRules.canMerge(LauncherItemType.APPWIDGET, LauncherItemType.APPLICATION))
        assertFalse(FolderRules.canMerge(LauncherItemType.APPLICATION, LauncherItemType.APPWIDGET))
        // Nesting folders is a maze.
        assertFalse(FolderRules.canMerge(LauncherItemType.FOLDER, LauncherItemType.APPLICATION))
    }

    @Test
    fun `a folder collapses below two children`() {
        assertTrue(FolderRules.shouldCollapse(0))
        assertTrue(FolderRules.shouldCollapse(1))
        assertFalse(FolderRules.shouldCollapse(2))
    }

    @Test
    fun `ranks stay dense after a removal`() {
        // Removing rank 1 must not leave a hole for the next insert to land in.
        assertEquals(mapOf(7L to 0, 9L to 1), FolderRules.denseRanks(listOf(7L, 9L)))
        assertEquals(0, FolderRules.nextRank(emptyList()))
        assertEquals(3, FolderRules.nextRank(listOf(0, 1, 2)))
    }
}

class ReconcileUseCaseTest {

    private val here = PackageKey("com.example.here", 0)
    private val work = PackageKey("com.example.work", 10)

    private fun app(id: Long, key: PackageKey, hidden: Boolean = false) = ReconcileUseCase.Item(
        id = id,
        type = LauncherItemType.APPLICATION,
        packageName = key.packageName,
        profileSerial = key.profileSerial,
        appWidgetId = null,
        hidden = hidden,
    )

    @Test
    fun `an installed and visible app is left alone`() {
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, here)),
            installed = setOf(here),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an uninstalled app is deleted`() {
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, here)),
            installed = emptySet(),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertEquals(listOf(ReconcileUseCase.Action.Delete(1)), actions)
    }

    @Test
    fun `a merely unavailable app is hidden, never deleted`() {
        // This is the whole point of the hidden column: turning a work profile off must
        // not wipe its icons off the home screen for good.
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, work)),
            installed = emptySet(),
            unavailable = setOf(work),
            boundWidgetIds = emptySet(),
        )
        assertEquals(listOf(ReconcileUseCase.Action.SetHidden(1, true)), actions)
    }

    @Test
    fun `an app that comes back is shown again`() {
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, work, hidden = true)),
            installed = setOf(work),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertEquals(listOf(ReconcileUseCase.Action.SetHidden(1, false)), actions)
    }

    @Test
    fun `an already-hidden unavailable app produces no redundant write`() {
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, work, hidden = true)),
            installed = emptySet(),
            unavailable = setOf(work),
            boundWidgetIds = emptySet(),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an app in a profile that is off is hidden even with no unavailable callback`() {
        // Cold start with the work profile already in quiet mode: no
        // onPackagesUnavailable has been received, so `unavailable` is empty
        // and the package is absent from `installed`. Only the profile
        // signal stands between this row and deletion.
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, work)),
            installed = setOf(here),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
            unavailableProfiles = setOf(work.profileSerial),
        )
        assertEquals(listOf(ReconcileUseCase.Action.SetHidden(1, true)), actions)
    }

    @Test
    fun `an already-hidden app in an off profile produces no redundant write`() {
        val actions = ReconcileUseCase.reconcile(
            items = listOf(app(1, work, hidden = true)),
            installed = setOf(here),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
            unavailableProfiles = setOf(work.profileSerial),
        )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `a widget whose id the host has forgotten is deleted`() {
        val widget = ReconcileUseCase.Item(
            id = 5,
            type = LauncherItemType.APPWIDGET,
            packageName = "com.example.widget",
            profileSerial = 0,
            appWidgetId = 42,
            hidden = false,
        )
        assertEquals(
            listOf(ReconcileUseCase.Action.Delete(5)),
            ReconcileUseCase.reconcile(listOf(widget), emptySet(), emptySet(), setOf(7)),
        )
        assertTrue(
            ReconcileUseCase.reconcile(listOf(widget), emptySet(), emptySet(), setOf(42)).isEmpty(),
        )
    }

    @Test
    fun `folders are not judged by package, only by their children`() {
        val folder = ReconcileUseCase.Item(
            id = 3,
            type = LauncherItemType.FOLDER,
            packageName = null,
            profileSerial = 0,
            appWidgetId = null,
            hidden = false,
        )
        assertTrue(ReconcileUseCase.reconcile(listOf(folder), emptySet(), emptySet(), emptySet()).isEmpty())
    }
}

class WidgetResizeTest {

    private val spec = GridSpec(columns = 4, rows = 4)

    @Test
    fun `dragging the right edge grows the span without moving the origin`() {
        val resized = WidgetResize.resized(CellRect(0, 0, 2, 1), WidgetResize.Edge.Right, 1)
        assertEquals(CellRect(0, 0, 3, 1), resized)
    }

    @Test
    fun `dragging the left edge left grows the span and moves the origin`() {
        // The whole point: the right edge must stay where it was, or the widget looks like it
        // slid rather than grew.
        val resized = WidgetResize.resized(CellRect(2, 0, 2, 1), WidgetResize.Edge.Left, -1)
        assertEquals(CellRect(1, 0, 3, 1), resized)
        assertEquals(4, resized!!.right)
    }

    @Test
    fun `dragging the top edge up grows downwards-anchored height`() {
        val resized = WidgetResize.resized(CellRect(0, 2, 1, 2), WidgetResize.Edge.Top, -1)
        assertEquals(CellRect(0, 1, 1, 3), resized)
        assertEquals(4, resized!!.bottom)
    }

    @Test
    fun `a span cannot be shrunk out of existence`() {
        assertNull(WidgetResize.resized(CellRect(0, 0, 1, 1), WidgetResize.Edge.Right, -1))
        assertNull(WidgetResize.resized(CellRect(0, 0, 1, 1), WidgetResize.Edge.Left, 1))
        assertNull(WidgetResize.resized(CellRect(0, 0, 1, 1), WidgetResize.Edge.Bottom, -1))
    }

    @Test
    fun `an origin cannot be dragged off the grid`() {
        assertNull(WidgetResize.resized(CellRect(0, 0, 2, 1), WidgetResize.Edge.Left, -1))
        assertNull(WidgetResize.resized(CellRect(0, 0, 1, 2), WidgetResize.Edge.Top, -1))
    }

    @Test
    fun `a no-op drag reports no change`() {
        assertNull(WidgetResize.resized(CellRect(1, 1, 2, 2), WidgetResize.Edge.Right, 0))
    }

    @Test
    fun `growth into an occupied cell is refused`() {
        val current = CellRect(0, 0, 2, 1)
        val neighbour = CellRect(2, 0)
        assertFalse(
            WidgetResize.canPlace(CellRect(0, 0, 3, 1), current, listOf(neighbour), spec),
        )
        // The same growth downwards is clear, so it is allowed.
        assertTrue(
            WidgetResize.canPlace(CellRect(0, 0, 2, 2), current, listOf(neighbour), spec),
        )
    }

    @Test
    fun `growth past the edge of the grid is refused`() {
        val current = CellRect(2, 0, 2, 1)
        assertFalse(WidgetResize.canPlace(CellRect(2, 0, 3, 1), current, emptyList(), spec))
    }

    @Test
    fun `the widget's own cells do not block its own growth`() {
        // others excludes the widget being resized; if it did not, every enlargement would be
        // refused because a growing rectangle always covers where it already was.
        val current = CellRect(0, 0, 2, 1)
        assertTrue(WidgetResize.canPlace(CellRect(0, 0, 3, 1), current, emptyList(), spec))
    }

    @Test
    fun `growth into a neighbour pushes it instead of being refused`() {
        val moves = WidgetResize.resizeWithPush(
            spec,
            candidate = CellRect(0, 0, 3, 1),
            others = mapOf(9L to CellRect(2, 0)),
            direction = PushDirection.Right,
        )
        assertEquals(mapOf(9L to CellRect(3, 0)), moves)
    }

    @Test
    fun `growth with nowhere to push the neighbour is refused`() {
        val moves = WidgetResize.resizeWithPush(
            spec,
            candidate = CellRect(0, 0, 3, 1),
            others = mapOf(9L to CellRect(2, 0), 8L to CellRect(3, 0)),
            direction = PushDirection.Right,
        )
        assertNull(moves)
    }

    @Test
    fun `growth past the edge of the grid is still refused`() {
        assertNull(
            WidgetResize.resizeWithPush(
                spec,
                candidate = CellRect(2, 0, 3, 1),
                others = emptyMap(),
                direction = PushDirection.Right,
            ),
        )
    }

    @Test
    fun `a shrink pushes nothing`() {
        val moves = WidgetResize.resizeWithPush(
            spec,
            candidate = CellRect(0, 0, 1, 1),
            others = mapOf(9L to CellRect(2, 0)),
            direction = PushDirection.Left,
        )
        assertEquals(emptyMap(), moves)
    }

    @Test
    fun `each edge shoves the way it is being dragged`() {
        assertEquals(PushDirection.Right, WidgetResize.pushDirection(WidgetResize.Edge.Right))
        assertEquals(PushDirection.Left, WidgetResize.pushDirection(WidgetResize.Edge.Left))
        assertEquals(PushDirection.Up, WidgetResize.pushDirection(WidgetResize.Edge.Top))
        assertEquals(PushDirection.Down, WidgetResize.pushDirection(WidgetResize.Edge.Bottom))
    }
}

class ContainerRefTest {

    @Test
    fun `container sentinels round-trip`() {
        listOf(ContainerRef.Desktop, ContainerRef.Hotseat, ContainerRef.Folder(17)).forEach {
            assertEquals(it, containerRefOf(it.toRaw()))
        }
    }

    @Test
    fun `sentinels cannot collide with a row id`() {
        // Row ids are autogenerated and therefore positive, so negative sentinels are safe.
        assertTrue(CONTAINER_DESKTOP < 0)
        assertTrue(CONTAINER_HOTSEAT < 0)
    }
}

class GridPreviewTest {

    private val spec = GridSpec(columns = 3, rows = 3)

    @Test
    fun `an empty cell takes the item and moves nothing`() {
        val plan = GridPreview.plan(spec, mapOf(1L to CellRect(0, 0)), draggedId = 1, wanted = CellRect(2, 2))
        assertEquals(CellRect(2, 2), plan?.target)
        assertEquals(emptyMap(), plan?.displaced)
    }

    @Test
    fun `an occupied cell displaces its occupant into a free neighbour`() {
        val placed = mapOf(1L to CellRect(0, 0), 2L to CellRect(1, 0))
        val plan = GridPreview.plan(spec, placed, draggedId = 1, wanted = CellRect(1, 0))
        // The finger gets the cell it is over, which is the whole point of a reorder.
        assertEquals(CellRect(1, 0), plan?.target)
        val moved = plan?.displaced?.get(2L)
        assertTrue(moved != null && moved != CellRect(1, 0))
    }

    @Test
    fun `an item released on its own cell disturbs nothing`() {
        val placed = mapOf(1L to CellRect(1, 1), 2L to CellRect(0, 0))
        val plan = GridPreview.plan(spec, placed, draggedId = 1, wanted = CellRect(1, 1))
        assertEquals(CellRect(1, 1), plan?.target)
        assertEquals(emptyMap(), plan?.displaced)
    }

    @Test
    fun `every occupant of a widget-sized hole is pushed somewhere different`() {
        // A 2x2 dropped over four separate icons: all four have to move, and no two of them may
        // be sent to the same cell.
        val placed = mapOf(
            1L to CellRect(0, 0),
            2L to CellRect(1, 0),
            3L to CellRect(0, 1),
            4L to CellRect(1, 1),
            9L to CellRect(2, 2),
        )
        val plan = GridPreview.plan(spec, placed, draggedId = 9, wanted = CellRect(0, 0, 2, 2))
        assertEquals(CellRect(0, 0, 2, 2), plan?.target)
        val moves = plan!!.displaced
        assertEquals(setOf(1L, 2L, 3L, 4L), moves.keys)
        assertEquals(4, moves.values.distinct().size)
        // And none of them into the cells the widget is taking.
        assertTrue(moves.values.none { it.overlaps(CellRect(0, 0, 2, 2)) })
    }

    @Test
    fun `the hole the dragged item left is where its neighbour is pushed`() {
        // A full page, so the only place the occupant of the wanted cell can go is the cell the
        // dragged item is vacating. Dragging one icon onto another therefore swaps them.
        val placed = buildMap {
            var id = 1L
            for (y in 0 until 3) {
                for (x in 0 until 3) put(id++, CellRect(x, y))
            }
        }
        val dragged = placed.entries.first { it.value == CellRect(2, 2) }.key
        val occupant = placed.entries.first { it.value == CellRect(0, 0) }.key
        val plan = GridPreview.plan(spec, placed, dragged, wanted = CellRect(0, 0))
        assertEquals(CellRect(0, 0), plan?.target)
        assertEquals(mapOf(occupant to CellRect(2, 2)), plan?.displaced)
    }

    @Test
    fun `a push with nowhere to go falls back rather than rearranging the page`() {
        // A 3x1 widget across the top, and one icon in each remaining row placed so that no whole
        // row is free. The widget cannot be pushed anywhere, so the drop must not push it - and
        // must not half-push the page either. It falls back to the nearest hole instead.
        val placed = mapOf(
            1L to CellRect(0, 0, 3, 1),
            2L to CellRect(1, 1),
            3L to CellRect(1, 2),
        )
        val plan = GridPreview.plan(spec, placed, draggedId = null, wanted = CellRect(0, 0))
        assertEquals(emptyMap(), plan?.displaced)
        val target = plan?.target
        assertTrue(target != null && !target.overlaps(CellRect(0, 0, 3, 1)))
    }

    @Test
    fun `a page with no room at all has no plan`() {
        val placed = mapOf(1L to CellRect(0, 0, 3, 3))
        assertNull(GridPreview.plan(spec, placed, draggedId = null, wanted = CellRect(0, 0)))
    }

    @Test
    fun `the same hover always plans the same layout`() {
        // The preview is recomputed every frame of a drag; a plan that varied between two
        // equivalent holes would show up as flicker, and would then commit as a jump.
        val placed = mapOf(1L to CellRect(1, 1), 2L to CellRect(0, 0), 3L to CellRect(2, 2))
        val first = GridPreview.plan(spec, placed, draggedId = 3, wanted = CellRect(1, 1))
        val second = GridPreview.plan(spec, placed, draggedId = 3, wanted = CellRect(1, 1))
        assertEquals(first, second)
    }

    @Test
    fun `a known direction pushes along it rather than into the nearest hole`() {
        // Dragging rightwards onto the middle icon of a row: the occupant must move right, into
        // the free cell beyond it, not up into the closer hole above.
        val placed = mapOf(1L to CellRect(1, 1), 2L to CellRect(0, 1))
        val plan = GridPreview.plan(
            spec,
            placed,
            draggedId = 2,
            wanted = CellRect(1, 1),
            direction = PushDirection.Right,
        )
        assertEquals(CellRect(1, 1), plan?.target)
        assertEquals(mapOf(1L to CellRect(2, 1)), plan?.displaced)
    }

    @Test
    fun `a direction that hits a wall falls back to the nearest hole`() {
        // Pushing right from the last column has nowhere to go, so the plan reverts to the
        // undirected behaviour rather than refusing the drop.
        val placed = mapOf(1L to CellRect(2, 0), 2L to CellRect(0, 0))
        val plan = GridPreview.plan(
            spec,
            placed,
            draggedId = 2,
            wanted = CellRect(2, 0),
            direction = PushDirection.Right,
        )
        assertEquals(CellRect(2, 0), plan?.target)
        assertEquals(1, plan?.displaced?.size)
        assertTrue(plan?.displaced?.get(1L)?.overlaps(CellRect(2, 0)) == false)
    }
}

class GridReorderTest {

    private val spec = GridSpec(columns = 4, rows = 4)

    @Test
    fun `direction comes from the dominant axis, ties going horizontal`() {
        assertEquals(PushDirection.Right, GridReorder.directionOf(CellRect(0, 0), CellRect(2, 1)))
        assertEquals(PushDirection.Left, GridReorder.directionOf(CellRect(2, 0), CellRect(0, 0)))
        assertEquals(PushDirection.Down, GridReorder.directionOf(CellRect(0, 0), CellRect(0, 2)))
        assertEquals(PushDirection.Up, GridReorder.directionOf(CellRect(0, 3), CellRect(0, 1)))
        // Equal on both axes: horizontal, so a diagonal drag still cascades one way only.
        assertEquals(PushDirection.Right, GridReorder.directionOf(CellRect(0, 0), CellRect(1, 1)))
        assertNull(GridReorder.directionOf(CellRect(1, 1), CellRect(1, 1)))
    }

    @Test
    fun `one occupant is shoved one cell along the drag`() {
        val others = mapOf(1L to CellRect(1, 0))
        val moves = GridReorder.pushAlong(spec, others, CellRect(1, 0), PushDirection.Right)
        assertEquals(mapOf(1L to CellRect(2, 0)), moves)
    }

    @Test
    fun `the cascade carries on through whatever the first push lands on`() {
        val others = mapOf(1L to CellRect(1, 0), 2L to CellRect(2, 0))
        val moves = GridReorder.pushAlong(spec, others, CellRect(1, 0), PushDirection.Right)
        assertEquals(mapOf(1L to CellRect(2, 0), 2L to CellRect(3, 0)), moves)
    }

    @Test
    fun `a cascade that runs out of grid is abandoned whole`() {
        // Three icons filling the row to its right edge: the last has nowhere to go, so none of
        // them may move. Half a cascade would leave two items in one cell.
        val others = mapOf(1L to CellRect(1, 0), 2L to CellRect(2, 0), 3L to CellRect(3, 0))
        assertNull(GridReorder.pushAlong(spec, others, CellRect(1, 0), PushDirection.Right))
    }

    @Test
    fun `a widget-sized occupant moves by its own span, not by one cell`() {
        val others = mapOf(1L to CellRect(1, 0, 2, 1))
        val moves = GridReorder.pushAlong(spec, others, CellRect(0, 0, 2, 1), PushDirection.Right)
        // Clear of the whole 2-wide region, which means starting at its right edge.
        assertEquals(mapOf(1L to CellRect(2, 0, 2, 1)), moves)
    }

    @Test
    fun `nothing is pushed into the cells the drag is taking`() {
        val others = mapOf(1L to CellRect(0, 1), 2L to CellRect(1, 1))
        val moves = GridReorder.pushAlong(spec, others, CellRect(0, 1, 2, 1), PushDirection.Down)
        assertTrue(moves!!.values.none { it.overlaps(CellRect(0, 1, 2, 1)) })
    }

    @Test
    fun `a region off the grid cannot be made room for`() {
        assertNull(GridReorder.pushAlong(spec, emptyMap(), CellRect(3, 0, 2, 1), PushDirection.Right))
    }
}

class ReorderDwellTest {

    private val plan = DropPlan(CellRect(1, 1), mapOf(2L to CellRect(2, 1)))

    private fun dwell() = ReorderDwell(timeoutMillis = 650)

    @Test
    fun `a rearrangement is not committed before the timeout`() {
        val dwell = dwell()
        assertFalse(dwell.update(0, plan))
        assertFalse(dwell.update(649, plan))
        assertNull(dwell.committed)
    }

    @Test
    fun `it commits once the timeout has passed`() {
        val dwell = dwell()
        dwell.update(0, plan)
        assertTrue(dwell.update(650, plan))
        assertEquals(plan, dwell.committed)
    }

    @Test
    fun `a new candidate restarts the clock`() {
        val dwell = dwell()
        dwell.update(0, plan)
        val other = DropPlan(CellRect(2, 2), mapOf(3L to CellRect(0, 0)))
        assertFalse(dwell.update(600, other))
        assertFalse(dwell.update(1000, other))
        assertNull(dwell.committed)
        assertTrue(dwell.update(1250, other))
    }

    @Test
    fun `committing is idempotent while the finger stays put`() {
        val dwell = dwell()
        dwell.update(0, plan)
        assertTrue(dwell.update(650, plan))
        assertFalse(dwell.update(900, plan))
        assertFalse(dwell.update(2000, plan))
    }

    @Test
    fun `a plan that displaces nothing commits on arrival`() {
        // Moving into an empty cell has no rearrangement to be careful about, and making it wait
        // 650ms would make the grid feel unresponsive.
        val dwell = dwell()
        assertTrue(dwell.update(0, DropPlan(CellRect(0, 0))))
    }

    @Test
    fun `what was dwelt on survives the finger moving away before release`() {
        // The whole reason the dwell exists as state rather than as a timer: the release point is
        // not necessarily the dwelt point, and the drop must write what the user watched happen.
        val dwell = dwell()
        dwell.update(0, plan)
        dwell.update(650, plan)
        dwell.update(700, DropPlan(CellRect(2, 2), mapOf(9L to CellRect(0, 2))))
        assertEquals(plan, dwell.committed)
    }
}

class FolderMergeTest {

    // A cell wider and taller than the icon, as a real grid cell is.
    private val icon = 48f
    private val cellW = 96f
    private val cellH = 120f

    private fun progress(dx: Float, dy: Float) =
        FolderMerge.mergeProgress(dx, dy, icon, cellW, cellH)

    private fun merges(dx: Float, dy: Float) =
        FolderMerge.willCreateFolder(dx, dy, icon, cellW, cellH)

    @Test
    fun `the radius is the mean of the cell's nearest edge and the icon's visible half`() {
        // Launcher3's getFolderCreationRadius: (reorderRadius + 0.92 * iconSize / 2) / 2, where the
        // reorder radius is the distance to the nearest cell edge - here half of 96.
        val expected = (48f + 0.92f * 48f / 2f) / 2f
        assertEquals(expected, FolderMerge.radius(icon, cellW, cellH))
    }

    @Test
    fun `dead centre always merges`() {
        assertTrue(merges(0f, 0f))
        assertEquals(1f, progress(0f, 0f))
    }

    @Test
    fun `the threshold grows with the cell rather than with the icon alone`() {
        // The same icon in a wider cell reaches further, which is the whole point of taking the
        // cell's own geometry into account: on a three-column grid the cells are much wider.
        val narrow = FolderMerge.radius(icon, 60f, 60f)
        val wide = FolderMerge.radius(icon, 200f, 200f)
        assertTrue(wide > narrow)
    }

    @Test
    fun `just inside the radius merges and just outside it does not`() {
        val r = FolderMerge.radius(icon, cellW, cellH)
        assertTrue(merges(r - 1f, 0f))
        assertFalse(merges(r + 1f, 0f))
    }

    @Test
    fun `distance is radial, so a diagonal is judged by both axes`() {
        val r = FolderMerge.radius(icon, cellW, cellH)
        // Each component alone is inside the radius; together they are outside it.
        val component = r * 0.8f
        assertTrue(merges(component, 0f))
        assertFalse(merges(component, component))
    }

    @Test
    fun `progress falls off towards the threshold`() {
        val r = FolderMerge.radius(icon, cellW, cellH)
        assertTrue(progress(2f, 0f) > progress(r * 0.9f, 0f))
        assertEquals(0f, progress(r, 0f))
    }
}

class HotseatArrangeTest {

    @Test
    fun `an insert into a row with room renumbers and evicts nothing`() {
        val plan = HotseatArrange.arrange(listOf(1L, 2L), id = 3, toRank = 1, slots = 4)
        assertEquals(mapOf(1L to 0, 3L to 1, 2L to 2), plan.ranks)
        assertNull(plan.evicted)
    }

    @Test
    fun `an insert into a full row pushes the tail out`() {
        // The bug this closes: without an eviction the row keeps four items in a three-slot
        // hotseat, and the fourth is never drawn anywhere.
        val plan = HotseatArrange.arrange(listOf(1L, 2L, 3L), id = 4, toRank = 0, slots = 3)
        assertEquals(4L, plan.ranks.keys.first { plan.ranks[it] == 0 })
        assertEquals(3L, plan.evicted)
        assertEquals(3, plan.ranks.size)
    }

    @Test
    fun `reordering within the row moves nothing out of it`() {
        val plan = HotseatArrange.arrange(listOf(1L, 2L, 3L), id = 3, toRank = 0, slots = 3)
        assertEquals(mapOf(3L to 0, 1L to 1, 2L to 2), plan.ranks)
        assertNull(plan.evicted)
    }

    @Test
    fun `a rank past the end of a full row lands in the last slot`() {
        val plan = HotseatArrange.arrange(listOf(1L, 2L, 3L), id = 4, toRank = 9, slots = 3)
        assertEquals(2, plan.ranks[4L])
        assertEquals(3L, plan.evicted)
    }
}

class DropBarTargetsTest {

    @Test
    fun `a user app offers Uninstall and a system app offers App info instead`() {
        assertEquals(
            DropBarSecondary.Uninstall,
            DropBarTargets.secondaryFor(LauncherItemType.APPLICATION, canUninstall = true),
        )
        assertEquals(
            DropBarSecondary.AppInfo,
            DropBarTargets.secondaryFor(LauncherItemType.APPLICATION, canUninstall = false),
        )
    }

    @Test
    fun `a shortcut never offers to uninstall the app it came from`() {
        assertEquals(
            DropBarSecondary.AppInfo,
            DropBarTargets.secondaryFor(LauncherItemType.DEEP_SHORTCUT, canUninstall = true),
        )
    }

    @Test
    fun `a widget and a folder have no second target`() {
        assertNull(DropBarTargets.secondaryFor(LauncherItemType.APPWIDGET, canUninstall = true))
        assertNull(DropBarTargets.secondaryFor(LauncherItemType.FOLDER, canUninstall = true))
    }
}

class PageCountTest {

    @Test
    fun `the spare page exists only while something is being dragged`() {
        assertEquals(2, PageCount.pageCount(maxOccupied = 1, isDragging = false))
        assertEquals(3, PageCount.pageCount(maxOccupied = 1, isDragging = true))
    }

    @Test
    fun `an empty workspace still has one page`() {
        assertEquals(1, PageCount.pageCount(maxOccupied = -1, isDragging = false))
        assertEquals(1, PageCount.pageCount(maxOccupied = -1, isDragging = true))
    }
}

class FastScrollTest {

    @Test
    fun `a strip with no sections has nothing to scroll to`() {
        assertNull(FastScroll.sectionAt(0.5f, sections = 0))
        assertEquals(0f, FastScroll.fractionOf(0, sections = 0))
    }

    @Test
    fun `a single section owns the whole strip`() {
        assertEquals(0, FastScroll.sectionAt(0f, sections = 1))
        assertEquals(0, FastScroll.sectionAt(0.5f, sections = 1))
        assertEquals(0, FastScroll.sectionAt(1f, sections = 1))
    }

    @Test
    fun `both ends of the strip are reachable`() {
        // The last section is the one that gets lost if the fraction is scaled by count - 1: it
        // becomes selectable only at exactly 1.0, which no finger ever reports.
        assertEquals(0, FastScroll.sectionAt(0f, sections = 4))
        assertEquals(3, FastScroll.sectionAt(1f, sections = 4))
        assertEquals(3, FastScroll.sectionAt(0.99f, sections = 4))
    }

    @Test
    fun `a finger dragged past either end clamps rather than wraps`() {
        assertEquals(0, FastScroll.sectionAt(-2f, sections = 4))
        assertEquals(3, FastScroll.sectionAt(5f, sections = 4))
    }

    @Test
    fun `a section sits at the middle of its own band`() {
        assertEquals(0.125f, FastScroll.fractionOf(0, sections = 4))
        assertEquals(0.875f, FastScroll.fractionOf(3, sections = 4))
        // Out of range clamps, so the bubble cannot be asked to sit off the strip.
        assertEquals(0.875f, FastScroll.fractionOf(9, sections = 4))
    }
}
