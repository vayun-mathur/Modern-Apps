package com.vayunmathur.clock.ui

import android.app.Application
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
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
                Thread.sleep(400)
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun dragInPagePersistsNewOrder() {
        val ds = DataStoreUtils.getInstance(ctx)
        runBlocking { WorldClockCities.set(ds, listOf("Tokyo", "London")) }
        val vm = ClockViewModel(ctx, ClockRepository.get(ctx))
        val stack = NavBackStack(arrayOf<Route>(Route.Main()))
        rule.setContent { ClockPage(stack, ds, vm) }
        // Cities load from the VM's asset parse; wait for both rows.
        val deadline = System.currentTimeMillis() + 15_000
        var rows = 0
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            rows = rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes().size
            if (rows == 2) break
            Thread.sleep(100)
        }
        assertEquals(2, rows)
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

        val persistDeadline = System.currentTimeMillis() + 10_000
        var stored: List<String> = emptyList()
        while (System.currentTimeMillis() < persistDeadline) {
            stored = runBlocking { WorldClockCities.flow(ds).first() }
            if (stored == listOf("London", "Tokyo")) break
            Thread.sleep(100)
            rule.waitForIdle()
        }
        assertEquals(listOf("London", "Tokyo"), stored)
        runBlocking { WorldClockCities.set(ds, emptyList()) }
    }
}
