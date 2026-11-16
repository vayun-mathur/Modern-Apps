package com.vayunmathur.games.logicgate.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.logicgate.Navigation
import com.vayunmathur.games.logicgate.Route
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** W6: the real Navigation shell and its Game/Component pages over a live ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogicNavigationW6Test {

    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun vm(): LogicViewModel =
        LogicViewModel(app, compute = Dispatchers.Unconfined, io = Dispatchers.Unconfined)

    @Test
    fun navigation_showsProgressionThenSandboxTab() {
        val vm = vm()
        rule.setContent { Box(Modifier.size(900.dp, 700.dp)) { Navigation(vm) } }
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.games.logicgate.R.string.lg_tagline)).assertExists()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.games.logicgate.R.string.lg_tab_sandbox)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.games.logicgate.R.string.lg_sandbox_tagline)).assertExists()
    }

    @Test
    fun gamePage_opensLevel() {
        val vm = vm()
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.progression.value.levels.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(vm.progression.value.levels.isNotEmpty())
        rule.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                GamePage(NavBackStack(arrayOf(Route.Progression)), vm, "not")
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun componentPage_opensComponent() {
        val vm = vm()
        val deadline = System.currentTimeMillis() + 15_000
        while (vm.progression.value.levels.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        val id = vm.createComponent("W6 Gate", "W6")
        rule.setContent {
            Box(Modifier.size(900.dp, 700.dp)) {
                ComponentPage(NavBackStack(arrayOf(Route.Sandbox)), vm, id)
            }
        }
        rule.waitForIdle()
    }
}
