package com.vayunmathur.library.network

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vayunmathur.library.util.OfflineAware
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the doubled status-bar gap under the offline banner.
 *
 * Robolectric reports a zero status-bar inset by default, so this dispatches a
 * fake 80px status-bar inset to the compose root. The wrapped content mimics a
 * top app bar by applying [statusBarsPadding] itself: before the fix it was
 * pushed a second inset (80px) below the banner; after the fix it sits
 * directly beneath the banner (only the banner's 8dp bottom padding remains,
 * which is 8px at Robolectric's density of 1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineAwareInsetsTest {

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun contentSitsDirectlyBelowBannerWithoutSecondStatusBarInset() {
        composeRule.setContent {
            OfflineAware {
                Box(Modifier.fillMaxWidth().statusBarsPadding().height(10.dp).testTag("probe"))
            }
        }
        composeRule.runOnUiThread {
            val root = composeRule.activity.findViewById<android.view.View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(
                root,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 80, 0, 0))
                    .build(),
            )
            root.requestApplyInsets()
        }
        composeRule.waitForIdle()
        val bannerText = composeRule.onNodeWithText("No internet connection")
            .fetchSemanticsNode().boundsInRoot
        val probe = composeRule.onNodeWithTag("probe").fetchSemanticsNode().boundsInRoot
        // Banner text bottom + its 8dp bottom padding is the banner's bottom edge.
        assertEquals(8f, probe.top - bannerText.bottom, 0.5f)
    }
}
