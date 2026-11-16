package com.vayunmathur.clock.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Navigation
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.library.util.DataStoreUtils
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Navigation-level tests: stopwatch tab control plane and dialog routes. */
@RunWith(AndroidJUnit4::class)
class ClockNavigationDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule val rule = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as Application, ClockRepository.get(ctx))
    private fun ds() = DataStoreUtils.getInstance(ctx)

    @Test fun stopwatchTabControlsViewModelStopwatch() {
        val vm = vm()
        vm.resetStopwatch()
        rule.setContent { Navigation(ds(), vm, Route.Stopwatch) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Play")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertTrue(vm.stopwatchRunning.value)
        rule.onNodeWithContentDescription("Pause")
            .performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertFalse(vm.stopwatchRunning.value)
        vm.resetStopwatch()
    }

}
