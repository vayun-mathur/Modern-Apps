package com.vayunmathur.communicate.telephony

import android.app.Service
import android.content.Intent
import com.vayunmathur.communicate.data.CommunicateLine
import com.vayunmathur.communicate.data.call.CallCapabilities
import com.vayunmathur.communicate.data.call.InAppCallController
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: foreground call services driven through their start actions under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class ForegroundServicesSlice4Pass2Test {

    private val actions = mutableListOf<String>()
    private val controller = object : InAppCallController {
        override fun answer() { actions += "answer" }
        override fun reject() { actions += "reject" }
        override fun hangup() { actions += "hangup" }
        override fun setMuted(muted: Boolean) { actions += "muted=$muted" }
        override fun setSpeaker(on: Boolean) { actions += "speaker=$on" }
    }

    @BeforeTest
    fun setUp() {
        actions.clear()
        InAppCallRegistry.onEnded("reset")
        InAppCallRegistry.clearEnded()
        InAppCallRegistry.videoController = null
        InAppCallRegistry.dtmfController = null
        InAppCallRegistry.holdController = null
        InAppCallRegistry.connection = null
    }

    private fun startIncomingCall() {
        InAppCallRegistry.bind(CommunicateLine.Signal, controller, CallCapabilities.AudioOnly)
        InAppCallRegistry.onCallStarting(CommunicateLine.Signal, "peer-1", "Pat Lee", false, incoming = true)
    }

    @Test
    fun inAppCallServiceAnswerActionAnswersAndBindsNothing() {
        startIncomingCall()
        val service = Robolectric.setupService(InAppCallForegroundService::class.java)
        assertNull(service.onBind(Intent()))
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.inappcall.ANSWER_CALL" }, 0, 1,
        )
        assertEquals(Service.START_STICKY, result)
        assertEquals(listOf("answer"), actions.toList())
        service.onDestroy()
    }

    @Test
    fun inAppCallServiceHangupRejectsWhileIncoming() {
        startIncomingCall()
        val service = Robolectric.setupService(InAppCallForegroundService::class.java)
        service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.inappcall.HANGUP_CALL" }, 0, 1,
        )
        assertEquals(listOf("reject"), actions.toList())
        service.onDestroy()
    }

    @Test
    fun inAppCallServiceHangupHangsUpWhileOngoing() {
        startIncomingCall()
        InAppCallRegistry.onPhase(InAppCallPhase.Active)
        val service = Robolectric.setupService(InAppCallForegroundService::class.java)
        val result = service.onStartCommand(
            Intent().apply { action = "com.vayunmathur.communicate.inappcall.HANGUP_CALL" }, 0, 1,
        )
        assertEquals(Service.START_STICKY, result)
        assertEquals(listOf("hangup"), actions.toList())
        service.onDestroy()
    }

    @Test
    fun inAppCallServiceNullIntentStillGoesForeground() {
        startIncomingCall()
        val service = Robolectric.setupService(InAppCallForegroundService::class.java)
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(emptyList(), actions.toList())
        service.onDestroy()
    }

    @Test
    fun googleVoiceCallServiceActionsAndStopPaths() {
        // No GV call is registered, so answer/hangup forward into the manager as no-ops;
        // the contract under test is that the service enters the foreground and stays sticky.
        val service = Robolectric.setupService(GoogleVoiceCallForegroundService::class.java)
        assertNull(service.onBind(Intent()))
        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(
                Intent().apply { action = "com.vayunmathur.communicate.googlevoice.ANSWER_CALL" }, 0, 1,
            ),
        )
        assertEquals(
            Service.START_STICKY,
            service.onStartCommand(
                Intent().apply { action = "com.vayunmathur.communicate.googlevoice.HANGUP_CALL" }, 0, 2,
            ),
        )
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 3))
        service.onDestroy()
    }
}
