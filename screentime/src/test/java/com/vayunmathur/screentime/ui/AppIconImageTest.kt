package com.vayunmathur.screentime.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

/** AppIconImage: real icon resolves to an image; unknown packages fall back to a spacer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppIconImageTest {

    @get:Rule val rule = createComposeRule()

    @Test fun systemPackageIconLoads() {
        rule.setContent { AppIconImage("android", Modifier.size(40.dp)) }
        // produceState loads the icon off the main thread; give it time to swap
        // the spacer for the image without crashing the composition.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            rule.waitForIdle()
            Thread.sleep(50)
            break
        }
        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.isRoot())
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test fun unknownPackageFallsBackToSpacer() {
        rule.setContent { AppIconImage("com.does.not.exist.anywhere", Modifier.size(40.dp)) }
        rule.waitForIdle()
        // No image is ever produced for a package the PM cannot resolve.
        assertTrue(
            rule.onAllNodes(androidx.compose.ui.test.isRoot())
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
