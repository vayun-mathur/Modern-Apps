package com.vayunmathur.communicate.data

import android.Manifest
import com.vayunmathur.communicate.data.call.inAppCallPermissions
import com.vayunmathur.communicate.data.call.requiresInAppCallPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The call-start permission contract: which lines capture media in-app (and so must request
 * permission contextually at call start) and which permissions a voice vs video call needs.
 * SIM calls are carried by Telecom and RCS has no call path, so neither is gated.
 */
class CallPermissionRequirementTest {

    @Test
    fun `voice call needs microphone only`() {
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), inAppCallPermissions(video = false))
    }

    @Test
    fun `video call needs camera and microphone`() {
        assertEquals(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
            inAppCallPermissions(video = true),
        )
    }

    @Test
    fun `in-app media lines require call permissions`() {
        assertTrue(CommunicateLine.GoogleVoice.requiresInAppCallPermissions())
        assertTrue(CommunicateLine.WhatsApp.requiresInAppCallPermissions())
        assertTrue(CommunicateLine.Signal.requiresInAppCallPermissions())
    }

    @Test
    fun `telecom and messaging-only lines do not require call permissions`() {
        assertFalse(CommunicateLine.Sim.requiresInAppCallPermissions())
        assertFalse(CommunicateLine.Rcs.requiresInAppCallPermissions())
    }
}
