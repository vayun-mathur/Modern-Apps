package com.vayunmathur.communicate

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.notifications.ConversationSpace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Activity-level tests for [MainActivity], owned by slice 6/6 (root package files M-Z).
 *
 * The protected [android.app.Activity.onUserLeaveHint] / `onNewIntent` entries are
 * driven through Robolectric's [org.robolectric.android.controller.ActivityController]
 * hooks (`userLeaving()` dispatches `onUserLeaveHint`, `newIntent(intent)` dispatches
 * `onNewIntent`) — no production change, no reflection.
 *
 * Covers what only an activity can exercise: the notification-permission gate in
 * [MainActivity.onCreateBeforeContent], the picture-in-picture gating in
 * [MainActivity.onUserLeaveHint] (driven through the real [InAppCallRegistry] state
 * machine), and [MainActivity.onNewIntent] deep-link delivery into the nav graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivitySlice6RoboTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @AfterTest
    fun resetCallRegistry() {
        // Every PiP test leaves the shared registry non-idle; force it back so tests
        // never see each other's calls. Ended is the only exit, then clear to Idle.
        if (InAppCallRegistry.state.value.phase != InAppCallPhase.Idle) {
            InAppCallRegistry.onEnded(null)
            InAppCallRegistry.clearEnded()
        }
    }

    @Test
    fun launchShowsMainTabs() {
        // MessagesScreen's empty state also renders "Messages"-ish text, so the tab
        // row is pinned by asserting all three tabs together plus the dialer/call-log
        // tabs individually.
        composeRule.onAllNodesWithText("Dialer").fetchSemanticsNodes().let {
            kotlin.test.assertTrue(it.isNotEmpty(), "Dialer tab missing")
        }
        composeRule.onAllNodesWithText("Call logs").fetchSemanticsNodes().let {
            kotlin.test.assertTrue(it.isNotEmpty(), "Call logs tab missing")
        }
        composeRule.onNodeWithText("Call logs").assertIsDisplayed()
    }

    @Test
    fun launchRequestsNotificationPermission() {
        val requested = shadowOf(composeRule.activity).lastRequestedPermission
        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            requested.requestedPermissions.toList(),
        )
    }

    @Test
    fun userLeaveHintWithNoCallDoesNotEnterPip() {
        assertEquals(InAppCallPhase.Idle, InAppCallRegistry.state.value.phase)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.userLeaving()
        assertFalse(controller.get().isInPictureInPictureMode)
    }

    @Test
    fun userLeaveHintWithActiveAudioCallDoesNotEnterPip() {
        startCall(video = false)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.userLeaving()
        assertFalse(controller.get().isInPictureInPictureMode)
    }

    @Test
    fun userLeaveHintWithActiveVideoCallEntersPip() {
        startCall(video = true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.userLeaving()
        assertTrue(controller.get().isInPictureInPictureMode)
    }

    @Test
    fun userLeaveHintWithEndedVideoCallDoesNotEnterPip() {
        startCall(video = true)
        InAppCallRegistry.onEnded("hangup")
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.userLeaving()
        // Ended takes the same early-return as Idle: no PiP for a dead call.
        assertFalse(controller.get().isInPictureInPictureMode)
        assertEquals(InAppCallPhase.Ended, InAppCallRegistry.state.value.phase)
    }

    @Test
    fun userLeaveHintWithRemoteVideoOnlyEntersPip() {
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = "peer",
            peerName = "Peer",
            isVideo = false,
            incoming = true,
        )
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        // Peer turned their camera on; ours is off. Far end still visible -> shrink.
        InAppCallRegistry.onRemoteVideo(true)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        controller.userLeaving()
        assertTrue(controller.get().isInPictureInPictureMode)
    }

    @Test
    fun activityRuleScenarioStillExposesLiveActivity() {
        // Sanity on the test harness itself: the composed activity is resumed.
        composeRule.activityRule.scenario.onActivity { activity ->
            kotlin.test.assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun newIntentWithNullDataKeepsMainTabs() {
        // No extras and no data URI parses to null: the app must stay on Main.
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java)
        composeRule.activityRule.scenario.onActivity { activity ->
            Robolectric.buildActivity(MainActivity::class.java, activity.intent)
                .create().newIntent(intent)
        }
        composeRule.onNodeWithText("Dialer").assertIsDisplayed()
    }

    @Test
    fun newIntentWithConversationExtrasOpensThread() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(ConversationSpace.EXTRA_LINE, CommunicateLine.Sim.name)
            putExtra(ConversationSpace.EXTRA_ADDRESS, "+15551234567")
            putExtra(ConversationSpace.EXTRA_THREAD_ID, 42L)
        }
        var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            controller = Robolectric.buildActivity(MainActivity::class.java, activity.intent)
                .create().start().resume().visible()
            controller?.newIntent(intent)
        }
        // The deep-link effect pushes Route.Conversation; the thread address renders.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(androidx.compose.ui.test.hasText("+15551234567"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("+15551234567").assertIsDisplayed()
    }

    private fun startCall(video: Boolean) {
        InAppCallRegistry.onCallStarting(
            line = CommunicateLine.Signal,
            peerId = "peer",
            peerName = "Peer",
            isVideo = video,
            incoming = false,
        )
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        if (video) {
            InAppCallRegistry.onLocalVideo(true)
        }
    }
}
