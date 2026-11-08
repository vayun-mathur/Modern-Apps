package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 13: reorderable grid/list drag gestures end to end. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round13Test {

    @get:Rule val rule = createComposeRule()

    @Test fun gridLongPressDragReordersItems() {
        val started = mutableListOf<Boolean>()
        val stopped = mutableListOf<Boolean>()
        var order: List<Int> = emptyList()
        rule.setContent {
            val items = remember { mutableStateListOf(0, 1, 2, 3) }
            order = items.toList()
            val gridState = rememberLazyGridState()
            val reorder = rememberReorderableLazyGridState(gridState) { from, to ->
                items.add(to.index, items.removeAt(from.index))
            }
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState) {
                items(items, key = { "k$it" }) { item ->
                    ReorderableItem(reorderState = reorder, key = "k$item") {
                        Box(Modifier.height(80.dp)) {
                            Text(
                                "handle-$item",
                                Modifier.reorderGridDragHandle(
                                    reorderState = reorder,
                                    key = "k$item",
                                    itemCount = 4,
                                    onDragStarted = { started += true },
                                    onDragStopped = { stopped += true },
                                ),
                            )
                            Text("tile-$item")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf(0, 1, 2, 3), order)

        rule.onNodeWithText("handle-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            // Drag down in steps so the centre crosses the row below.
            repeat(6) {
                moveBy(Offset(0f, 60f))
                advanceEventTime(50)
            }
            up()
        }
        rule.waitForIdle()
        assertTrue(started.isNotEmpty(), "drag should have started")
        assertTrue(stopped.isNotEmpty(), "drag should have stopped")
        assertTrue(order.first() != 0, "item 0 should have moved from the front, order=$order")
        assertEquals(setOf(0, 1, 2, 3), order.toSet())
    }

    @Test fun gridHorizontalFlickFiresRelease() {
        val releases = mutableListOf<Pair<Float, Float>>()
        val drags = mutableListOf<Float>()
        rule.setContent {
            val gridState = rememberLazyGridState()
            val reorder = rememberReorderableLazyGridState(gridState) { _, _ -> }
            LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState) {
                items(listOf(0, 1), key = { "k$it" }) { item ->
                    Box(Modifier.height(80.dp)) {
                        Text(
                            "flick-$item",
                            Modifier.reorderGridDragHandle(
                                reorderState = reorder,
                                key = "k$item",
                                itemCount = 2,
                                horizontalFlick = HorizontalFlick(
                                    onDrag = { drags += it },
                                    onRelease = { dx, v -> releases += dx to v },
                                ),
                            ),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("flick-0").performTouchInput {
            down(center)
            // Fast sideways swipe: no long press, clearly horizontal travel.
            moveBy(Offset(60f, 0f))
            moveBy(Offset(80f, 2f))
            moveBy(Offset(80f, 2f))
            up()
        }
        rule.waitForIdle()
        assertTrue(drags.isNotEmpty(), "flick drag should be reported, drags=$drags")
        assertEquals(1, releases.size)
        assertTrue(releases.single().first > 100f, "release dx, was ${releases.single().first}")
    }

    // ---- DetailScaffold / DetailLazyColumn variants ----

    private object DHome : com.vayunmathur.library.util.NavKey
    private object DDetail : com.vayunmathur.library.util.NavKey

    @Test fun detailScaffoldBackStackBackButtonPops() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(DHome, DDetail),
        )
        rule.setContent {
            DetailScaffold(
                title = "Detail",
                backStack = stack,
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("detail-body") }
        }
        rule.onNodeWithText("detail-body").assertIsDisplayed()
        rule.onNodeWithText("Detail").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf(DHome), stack.backStack)
    }

    @Test fun detailScaffoldStyledTitleWithFillHeight() {
        rule.setContent {
            DetailScaffold(
                title = { Text("Styled title") },
                scrollBehavior = appBarScrollBehavior(),
                fillHeight = true,
                bottomBar = { Text("detail-bottom") },
            ) {
                Text("filled-body")
            }
        }
        rule.onNodeWithText("Styled title").assertIsDisplayed()
        rule.onNodeWithText("filled-body").assertIsDisplayed()
        rule.onNodeWithText("detail-bottom").assertIsDisplayed()
    }

    @Test fun detailLazyColumnBackStackBackButtonPops() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(DHome, DDetail),
        )
        rule.setContent {
            DetailLazyColumn(
                title = "List detail",
                backStack = stack,
                scrollBehavior = appBarScrollBehavior(),
            ) {
                items(listOf("Row A", "Row B")) { Text(it) }
            }
        }
        rule.onNodeWithText("Row A").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf(DHome), stack.backStack)
    }

    @Test fun detailSectionWithoutTitleShowsOnlyContent() {
        rule.setContent {
            DetailSection(title = null) { Text("untitled-content") }
        }
        rule.onNodeWithText("untitled-content").assertIsDisplayed()
        assertEquals(
            0,
            rule.onAllNodesWithText("untitled-content").fetchSemanticsNodes().size - 1,
        )
    }

    // ---- TabbedPagerScaffold BottomNav + swipe ----

    @Test fun tabbedPagerBottomNavSwitchesPages() {
        val tabs = listOf(
            PagerTab("First", content = { Text("Page one") }),
            PagerTab("Second", content = { Text("Page two") }),
        )
        rule.setContent {
            TabbedPagerScaffold(
                tabs = tabs,
                tabStyle = TabStyle.BottomNav,
                topBar = { Text("pager-top") },
                leadingBottomBar = { Text("now-playing") },
            )
        }
        rule.onNodeWithText("pager-top").assertIsDisplayed()
        rule.onNodeWithText("now-playing").assertIsDisplayed()
        rule.onNodeWithText("Page one").assertIsDisplayed()
        rule.onNodeWithText("Second").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Page two").assertIsDisplayed()
    }

    // No swipe test: HorizontalPager flings do not turn pages under
    // Robolectric's touch injection (tab clicks above cover page switching).

    @Test fun listDragHandleReordersItems() {
        var order: List<Int> = emptyList()
        rule.setContent {
            val items = remember { mutableStateListOf(0, 1, 2, 3) }
            order = items.toList()
            val listState = rememberLazyListState()
            val reorder = rememberReorderableLazyListState(listState) { from, to ->
                items.add(to.index, items.removeAt(from.index))
            }
            LazyColumn(state = listState) {
                items(items, key = { "k$it" }) { item ->
                    ReorderableItem(reorderState = reorder, key = "k$item") {
                        Box(Modifier.height(80.dp)) {
                            Text(
                                "rowhandle-$item",
                                Modifier.reorderDragHandle(reorder, "k$item"),
                            )
                            Text("row-$item")
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("rowhandle-0").performTouchInput {
            down(center)
            repeat(6) {
                moveBy(Offset(0f, 60f))
                advanceEventTime(50)
            }
            up()
        }
        rule.waitForIdle()
        assertTrue(order.first() != 0, "item 0 should have moved from the front, order=$order")
        assertEquals(setOf(0, 1, 2, 3), order.toSet())
    }
}
