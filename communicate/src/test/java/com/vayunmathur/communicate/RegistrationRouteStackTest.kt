package com.vayunmathur.communicate

import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Stack transitions for the registration wizards: steps push in flow
 * order carrying the entered number, conditional steps (WhatsApp PIN,
 * Signal captcha) only appear when demanded, and Done replaces the
 * whole registration sub-stack so leaving it exits registration.
 */
@RunWith(RobolectricTestRunner::class)
class RegistrationRouteStackTest {

    @Test
    fun `whatsApp done replaces number code and pin steps`() {
        val stack = NavBackStack(
            arrayOf<Route>(
                Route.Messages,
                Route.Accounts,
                Route.WhatsAppRegistration.Number,
                Route.WhatsAppRegistration.Code("1", "5550102030"),
                Route.WhatsAppRegistration.Pin("1", "5550102030"),
            ),
        )
        stack.showWhatsAppRegistrationDone("1", "5550102030")
        assertEquals(
            listOf(
                Route.Messages,
                Route.Accounts,
                Route.WhatsAppRegistration.Done("1", "5550102030"),
            ),
            stack.backStack,
        )
        stack.pop()
        assertEquals(listOf(Route.Messages, Route.Accounts), stack.backStack)
    }

    @Test
    fun `whatsApp done without a pin step still replaces the sub-stack`() {
        val stack = NavBackStack(
            arrayOf<Route>(
                Route.Accounts,
                Route.WhatsAppRegistration.Number,
                Route.WhatsAppRegistration.Code("44", "7700900123"),
            ),
        )
        stack.showWhatsAppRegistrationDone("44", "7700900123")
        assertEquals(
            listOf(Route.Accounts, Route.WhatsAppRegistration.Done("44", "7700900123")),
            stack.backStack,
        )
    }

    @Test
    fun `signal done replaces number and code steps`() {
        val stack = NavBackStack(
            arrayOf<Route>(
                Route.Accounts,
                Route.SignalRegistration.Number,
                Route.SignalRegistration.Code("1", "4155552671"),
            ),
        )
        stack.showSignalRegistrationDone("1", "4155552671")
        assertEquals(
            listOf(Route.Accounts, Route.SignalRegistration.Done("1", "4155552671")),
            stack.backStack,
        )
    }

    @Test
    fun `signal captcha carries the number and transport to resume`() {
        val captcha = Route.SignalRegistration.Captcha("1", "4155552671", "voice")
        assertEquals("1", captcha.cc)
        assertEquals("4155552671", captcha.number)
        assertEquals("voice", captcha.transport)
    }
}
