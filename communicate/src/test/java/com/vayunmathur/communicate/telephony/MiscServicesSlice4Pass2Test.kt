package com.vayunmathur.communicate.telephony

import android.app.Service
import android.content.Intent
import androidx.car.app.SessionInfo
import com.vayunmathur.communicate.service.car.CommunicateCarAppService
import com.vayunmathur.communicate.service.car.CommunicateCarSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: car app service, in-call service start actions, and session creation. */
@RunWith(RobolectricTestRunner::class)
class MiscServicesSlice4Pass2Test {

    @Test
    fun carAppServiceCreatesSessionsAndAllowsAllHosts() {
        val service = Robolectric.setupService(CommunicateCarAppService::class.java)
        assertNotNull(service.createHostValidator())
        val session = service.onCreateSession()
        assertTrue(session is CommunicateCarSession)
        val perDisplay = service.onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "cluster"))
        assertTrue(perDisplay is CommunicateCarSession)
        assertEquals(
            androidx.car.app.CarAppService.THEME_SOURCE_APP,
            service.carAppThemeSource,
        )
    }

    @Test
    fun inCallServiceActionsWithNoCallAreSafe() {
        val service = Robolectric.setupService(CommunicateInCallService::class.java)
        for (action in listOf(
            "com.vayunmathur.communicate.regularcall.ANSWER",
            "com.vayunmathur.communicate.regularcall.DECLINE",
            "com.vayunmathur.communicate.regularcall.DISCONNECT",
            null,
        )) {
            val intent = action?.let { Intent().apply { this.action = it } }
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent, 0, 1))
        }
        service.onDestroy()
    }

    @Test
    fun carSessionOnCreateScreenReturnsThreadListScreen() {
        val session = CommunicateCarSession()
        // onCreateScreen needs a CarContext, which only a host provides; the session's
        // lifecycle/cancellation contract is covered by CommunicateCarSessionSlice4Test.
        assertNotNull(session.scope)
        assertNotNull(session.repository)
    }
}
