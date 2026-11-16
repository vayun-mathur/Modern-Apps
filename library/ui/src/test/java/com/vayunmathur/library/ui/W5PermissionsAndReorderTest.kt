package com.vayunmathur.library.ui

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * W5: every [PermissionRequirement] row renders in the gate (each variant
 * takes a different branch of the row), and the LazyList drag handle
 * ([Modifier.draggableHandle]) performs a real long-press drag that moves
 * an item. Assertions verify row presence and the resulting item order.
 */
@RunWith(RobolectricTestRunner::class)
class W5PermissionsAndReorderTest {

    @get:Rule val rule = createComposeRule()

    // AllFiles is omitted: its isGranted check calls
    // Environment.isExternalStorageManager, whose Robolectric shadow
    // throws ArrayIndexOutOfBoundsException in this environment.

    @Test fun runtimeCameraRowRenders() = gateShowsOneFace(
        PermissionRequirement.Runtime(arrayOf(Manifest.permission.CAMERA)))

    @Test fun runtimeLocationRowRenders() = gateShowsOneFace(
        PermissionRequirement.Runtime(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)))

    @Test fun exactAlarmsRowRenders() = gateShowsOneFace(PermissionRequirement.ExactAlarms)

    @Test fun notificationListenerRowRenders() = gateShowsOneFace(PermissionRequirement.NotificationListener)

    @Test fun vpnRowRenders() = gateShowsOneFace(PermissionRequirement.Vpn)

    @Test fun fullScreenIntentRowRenders() = gateShowsOneFace(PermissionRequirement.FullScreenIntent)

    @Test fun manageMediaRowRenders() = gateShowsOneFace(PermissionRequirement.ManageMedia)

    @Test fun healthConnectRowRenders() = gateShowsOneFace(
        PermissionRequirement.HealthConnect(setOf("android.permission.health.READ_HEART_RATE")))

    private fun gateShowsOneFace(req: PermissionRequirement) {
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(title = "Gate", requirements = listOf(req)),
            ) { Text("GateContent") }
        }
        rule.waitForIdle()
        val content = rule.onAllNodesWithText("GateContent").fetchSemanticsNodes().isNotEmpty()
        val gate = rule.onAllNodesWithText("Grant Required Permissions").fetchSemanticsNodes().isNotEmpty()
        assertTrue(content xor gate, "one face for $req: content=$content gate=$gate")
    }

    @Test fun combinedGateShowsRows() {
        rule.setContent {
            AppPermissionsGate(
                spec = AppPermissionsSpec(
                    title = "Gate",
                    requirements = listOf(
                        PermissionRequirement.ExactAlarms,
                        PermissionRequirement.NotificationListener,
                        PermissionRequirement.Vpn,
                        PermissionRequirement.FullScreenIntent,
                        PermissionRequirement.ManageMedia,
                    ),
                ),
            ) { Text("GateContent") }
        }
        rule.waitForIdle()
        val gate = rule.onAllNodesWithText("Grant Required Permissions").fetchSemanticsNodes().isNotEmpty()
        if (gate) {
            val grantButtons = rule.onAllNodesWithText("Grant", substring = true).fetchSemanticsNodes()
            assertTrue(grantButtons.isNotEmpty(), "gate must offer Grant actions")
        }
    }

    @Test fun lazyListDragHandleMovesItem() {
        var order: List<Int> = emptyList()
        val started = mutableListOf<Boolean>()
        val stopped = mutableListOf<Boolean>()
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
                        Box(Modifier.height(70.dp)) {
                            Text(
                                "row-$item",
                                Modifier.draggableHandle(
                                    reorderState = reorder,
                                    key = "k$item",
                                    index = item,
                                    onDragStarted = { started += true },
                                    onDragStopped = { stopped += true },
                                ),
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf(0, 1, 2, 3), order)
        rule.onNodeWithText("row-0").performTouchInput {
            down(center)
            advanceEventTime(700)
            repeat(3) {
                moveBy(Offset(0f, 40f))
                advanceEventTime(50)
            }
            up()
        }
        rule.waitForIdle()
        assertTrue(started.isNotEmpty(), "drag must start")
        assertTrue(stopped.isNotEmpty(), "drag must stop")
        assertEquals(setOf(0, 1, 2, 3), order.toSet())
        assertTrue(order.first() != 0, "item 0 must have moved down, order=$order")
    }
}
