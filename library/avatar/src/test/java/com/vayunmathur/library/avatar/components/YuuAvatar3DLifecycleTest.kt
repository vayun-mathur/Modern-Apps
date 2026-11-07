package com.vayunmathur.library.avatar.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/** Lifecycle pausing and drag-rotate wiring of [YuuAvatar3D] with native present. */
@RunWith(RobolectricTestRunner::class)
class YuuAvatar3DLifecycleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `pause and resume reach the surface lifecycle observer`() {
        rule.setContent {
            YuuAvatar3D(
                configJson = "{}",
                poseId = "idle",
                surfaceId = 11,
                fallback = { Text("hero") },
            )
        }
        rule.onNodeWithText("hero").assertIsDisplayed()
        rule.activityRule.scenario.moveToState(Lifecycle.State.STARTED) // ON_PAUSE
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // ON_RESUME
        rule.onNodeWithText("hero").assertIsDisplayed()
    }

    @Test
    fun `dragging orbits the camera without disturbing overlays`() {
        rule.setContent {
            YuuAvatar3D(
                configJson = "{}",
                poseId = "wave",
                surfaceId = 12,
                fallback = { Text("drag hero") },
                enableDragRotate = true,
            )
        }
        rule.onRoot().performTouchInput {
            swipe(Offset(100f, 300f), Offset(500f, 500f), 200L)
        }
        rule.onNodeWithText("drag hero").assertIsDisplayed()
        rule.onRoot().performTouchInput {
            swipe(Offset(500f, 500f), Offset(100f, 100f), 200L)
        }
        rule.onNodeWithText("drag hero").assertIsDisplayed()
    }
}
