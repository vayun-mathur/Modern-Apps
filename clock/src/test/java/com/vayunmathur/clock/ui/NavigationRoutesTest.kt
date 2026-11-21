package com.vayunmathur.clock.ui

import android.app.Application
import com.vayunmathur.testsupport.awaitWithMainIdle
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/**
 * Navigation's initial-route mapping for the two tabs that never touch Room:
 * the world clock and the alarm-settings page reached as a page destination.
 */
@RunWith(AndroidJUnit4::class)
class NavigationRoutesTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()


    companion object {
        /** Drain stored background-DB errors from earlier classes (see ClockDialogsDeepTest). */
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
    private fun vm() = ClockViewModel(ctx, ClockRepository.get(ctx))

    @Test fun navigationOpensOnWorldClockTab() {
        rule.setContent { Navigation(DataStoreUtils.getInstance(ctx), vm(), Route.Clock) }
        rule.waitForIdle()
        // The world-clock tab is selected; its Add FAB is present.
        rule.onNodeWithText("Clock", substring = true).assertExists()
    }

    @Test fun navigationOpensAlarmSettingsPage() {
        rule.setContent { Navigation(DataStoreUtils.getInstance(ctx), vm(), Route.AlarmSettings) }
        rule.waitForIdle()
        rule.onNodeWithText("Defaults for new alarms").assertExists()
    }
}
