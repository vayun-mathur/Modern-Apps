package com.vayunmathur.library.avatar.components

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test

/**
 * Without `libyuu_avatar`, [YuuAvatar3D] must show the caller's fallback and
 * nothing else: that fallback is the shipped hero on every device without
 * the native engine, including the x86_64 emulator.
 */
@RunWith(RobolectricTestRunner::class)
class YuuAvatar3DTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `shows the fallback when native is unavailable`() {
        rule.setContent {
            YuuAvatar3D(
                configJson = "{}",
                poseId = "wave",
                surfaceId = 1,
                fallback = { Text("2D hero") },
            )
        }
        rule.onNodeWithText("2D hero").assertIsDisplayed()
    }

    @Test
    fun `shows the fallback for a null pose and drag mode too`() {
        rule.setContent {
            YuuAvatar3D(
                configJson = "{}",
                poseId = null,
                surfaceId = 2,
                fallback = { Text("fallback hero") },
                enableDragRotate = true,
            )
        }
        rule.onNodeWithText("fallback hero").assertIsDisplayed()
    }

    @Test
    fun `renders nothing without a fallback`() {
        rule.setContent {
            YuuAvatar3D(configJson = "{}", poseId = "idle", surfaceId = 3)
        }
        rule.onRoot().assertExists()
        rule.onNodeWithText("2D hero").assertDoesNotExist()
    }
}
