package com.vayunmathur.auto

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.platform.AutoSessionState
import com.vayunmathur.auto.platform.AutoViewModel
import com.vayunmathur.auto.platform.PairingViewModel
import com.vayunmathur.auto.telephony.CarProjectionInCallService
import com.vayunmathur.auto.R as AutoR
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class NavigationAndTelephonyTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @BeforeTest fun reset() { AutoSessionState.onDisconnected() }

    @Test fun `navigation home renders the auto screen`() {
        val autoVm = AutoViewModel()
        val pairingVm = PairingViewModel()
        rule.setContent { Navigation(autoVm, pairingVm) }
        rule.onNodeWithText(ctx.getString(AutoR.string.state_disconnected)).assertExists()
        rule.onNodeWithText(ctx.getString(AutoR.string.connect_hint)).assertExists()
    }

    @Test fun `route objects are distinct nav keys`() {
        assertEquals(Route.Home, Route.Home)
        assertEquals(true, Route.Pairing != Route.Home)
        assertEquals(true, Route.PinnedApps != Route.Pairing)
    }

    @Test fun `in-call companion actions with nothing bound are no-ops`() {
        assertNull(CarProjectionInCallService.current)
        CarProjectionInCallService.answerCall()
        CarProjectionInCallService.endCall()
        CarProjectionInCallService.toggleHold()
        CarProjectionInCallService.toggleMute()
        assertNull(CarProjectionInCallService.current)
        assertNull(AutoSessionState.activeCall.value)
    }
}
