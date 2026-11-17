package com.vayunmathur.communicate

import com.vayunmathur.communicate.ui.syncCallRoute

import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The call screen is a destination, not an overlay: [syncCallRoute]
 * projects the registry's phase onto the stack. Leaving Idle pushes
 * [Route.Call] over whatever was showing; returning to Idle pops it and
 * reveals the screen underneath. The registry/engine is untouched.
 */
@RunWith(RobolectricTestRunner::class)
class CallRouteSyncTest {

    private fun stackOf(vararg routes: Route) = NavBackStack(routes)

    @Test
    fun `leaving idle pushes the call over the current screen`() {
        val stack = stackOf(Route.Messages, Route.Accounts)
        syncCallRoute(stack, InAppCallPhase.Incoming)
        assertEquals(listOf(Route.Messages, Route.Accounts, Route.Call), stack.backStack)
    }

    @Test
    fun `live phases keep a single call destination on top`() {
        val stack = stackOf(Route.Messages)
        syncCallRoute(stack, InAppCallPhase.Outgoing)
        syncCallRoute(stack, InAppCallPhase.Connecting)
        syncCallRoute(stack, InAppCallPhase.Active)
        syncCallRoute(stack, InAppCallPhase.Ended)
        assertEquals(listOf(Route.Messages, Route.Call), stack.backStack)
    }

    @Test
    fun `returning to idle pops the call and reveals the previous screen`() {
        val conversation = Route.Conversation(threadId = 7L, address = "+15551234567")
        val stack = stackOf(Route.Messages, conversation)
        syncCallRoute(stack, InAppCallPhase.Active)
        syncCallRoute(stack, InAppCallPhase.Idle)
        assertEquals(listOf(Route.Messages, conversation), stack.backStack)
    }

    @Test
    fun `idle without a call on top leaves the stack untouched`() {
        val stack = stackOf(Route.Messages)
        syncCallRoute(stack, InAppCallPhase.Idle)
        assertEquals(listOf(Route.Messages), stack.backStack)
    }

    @Test
    fun `a destination pushed over the call does not bury it`() {
        val stack = stackOf(Route.Messages)
        syncCallRoute(stack, InAppCallPhase.Active)
        stack.add(Route.Accounts)
        syncCallRoute(stack, InAppCallPhase.Active)
        assertEquals(listOf(Route.Messages, Route.Accounts, Route.Call), stack.backStack)
    }
}
