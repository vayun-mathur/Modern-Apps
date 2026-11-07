package com.vayunmathur.launcher.tv.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.launcher.tv.platform.TvHomeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Compose tests (Robolectric + createComposeRule): [TvHomePage] wires a
 * real [TvHomeViewModel] to [TvHomeScreen] without crashing, and
 * [TvClockHeader] renders.
 */
@RunWith(AndroidJUnit4::class)
class LauncherTvPageTest {

    @get:Rule val rule = createComposeRule()

    @Test fun tvHomePageComposes() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = TvHomeViewModel(app)
        rule.setContent { TvHomePage(viewModel = vm) }
        // Empty grid under Robolectric: no tiles, but the clock header is there.
        assertEquals(0, rule.onAllNodesWithText("Alpha").fetchSemanticsNodes().size)
    }

    @Test fun clockHeaderRenders() {
        rule.setContent { TvClockHeader(onOpenNetworkSettings = {}) }
    }
}
