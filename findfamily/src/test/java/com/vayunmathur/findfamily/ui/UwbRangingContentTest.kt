package com.vayunmathur.findfamily.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.platform.UwbSessionManager
import com.vayunmathur.findfamily.platform.RangingSample
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test

/** Robolectric Compose tests for [UwbRangingContent] state rendering (no UWB hardware needed). */
@RunWith(AndroidJUnit4::class)
class UwbRangingContentTest {
    @get:Rule val rule = createComposeRule()

    @Test fun idleShowsStarting() {
        rule.setContent { UwbRangingContent("Alice", UwbSessionManager.UwbSessionState.Idle, {}) }
        rule.onNodeWithText("Alice").assertIsDisplayed()
    }
    @Test fun unsupportedShowsReason() {
        rule.setContent { UwbRangingContent("Bob", UwbSessionManager.UwbSessionState.Unsupported("No UWB chip"), {}) }
        rule.onNodeWithText("No UWB chip", substring = true).assertIsDisplayed()
    }
    @Test fun failedShowsReason() {
        rule.setContent { UwbRangingContent("Cid", UwbSessionManager.UwbSessionState.Failed("radio error"), {}) }
        rule.onNodeWithText("radio error", substring = true).assertIsDisplayed()
    }
    @Test fun rangingShowsDistance() {
        val sample = RangingSample(distanceMeters = 2.5f, azimuthDeg = 30f, elevationDeg = 5f, timestampNanos = 1L)
        rule.setContent { UwbRangingContent("Dee", UwbSessionManager.UwbSessionState.Ranging(sample), {}) }
        rule.onNodeWithText("2.5 m", substring = true).assertIsDisplayed()
    }
    @Test fun rangingWithoutAzimuthShowsHint() {
        val sample = RangingSample(distanceMeters = null, azimuthDeg = null, elevationDeg = null, timestampNanos = 1L)
        rule.setContent { UwbRangingContent("Eli", UwbSessionManager.UwbSessionState.Ranging(sample), {}) }
        rule.onAllNodesWithText("—", substring = true)[0].assertIsDisplayed()
    }
}
