package com.vayunmathur.clock.ui

import android.app.Application
import com.vayunmathur.testsupport.awaitWithMainIdle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.down
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.platform.WorldClockCities
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.vayunmathur.testsupport.awaitSemantics
import com.vayunmathur.testsupport.awaitValueBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ClockPage's reorder wiring: dragging a city in the bound page persists the
 * new order through WorldClockCities (the stateless screen's own drag is
 * covered in ScreensDirectTest; this is the page-level DataStore half).
 */
@RunWith(AndroidJUnit4::class)
class ClockPageDragTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    companion object {
        @org.junit.BeforeClass @JvmStatic fun drainStoredDbErrors() {
            repeat(8) {
                run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 25_000) { ++pumped >= 25_000 } }
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun dragInPagePersistsNewOrder() {
        val ds = DataStoreUtils.getInstance(ctx)
        runBlocking { WorldClockCities.set(ds, listOf("Tokyo", "London")) }
        val vm = ClockViewModel(ctx, ClockRepository.get(ctx))
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm))
        rule.setContent { ClockPage(stack, ds, vm) }
        // Cities load from the VM's asset parse; wait for both rows to compose.
        rule.awaitSemantics("both world-clock rows composed") {
            rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes().size == 2
        }
        assertEquals(2, rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes().size)
        rule.onNodeWithText("Tokyo").assertExists()
        rule.onNodeWithText("London").assertExists()

        val handles = rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes()
        val first = handles[0].positionInRoot
        val second = handles[1].positionInRoot
        rule.onAllNodesWithContentDescription("Reorder")[0].performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(0f, (second.y - first.y) + 40f))
            up()
        }
        rule.waitForIdle()

        // Terminal signal: the persisted world-clock flow emits the new order.
        val stored = awaitValueBlocking(WorldClockCities.flow(ds)) { it == listOf("London", "Tokyo") }
        assertEquals(listOf("London", "Tokyo"), stored)
        runBlocking { WorldClockCities.set(ds, emptyList()) }
    }
}
