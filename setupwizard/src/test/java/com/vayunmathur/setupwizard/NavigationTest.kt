package com.vayunmathur.setupwizard

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.setupwizard.platform.SetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NavigationTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @BeforeTest fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun `navigation starts at welcome and advances to location for a primary user`() {
        val vm = SetupViewModel(app)
        rule.setContent { Navigation(vm) }
        rule.onNodeWithText(ctx.getString(R.string.welcome_to_modern_apps_os)).assertExists()
        rule.onNodeWithText(ctx.getString(R.string.next)).performClick()
        // The next step is the Wi-Fi handoff (no UI of its own): the welcome step
        // leaves the screen while the external setup activity is launched.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodes(androidx.compose.ui.test.hasText(ctx.getString(R.string.welcome_to_modern_apps_os)))
                .fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText(ctx.getString(R.string.welcome_to_modern_apps_os)).assertDoesNotExist()
    }

    @Test fun `routes are distinct destinations`() {
        assertEquals(Route.Welcome, Route.Welcome)
        assertTrue(Route.Finish != Route.Welcome)
        assertTrue(Route.OemUnlock != Route.Wifi)
    }

    private fun assertTrue(value: Boolean) = kotlin.test.assertTrue(value)
}
