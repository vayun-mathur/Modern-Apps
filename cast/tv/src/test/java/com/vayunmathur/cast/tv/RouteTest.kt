package com.vayunmathur.cast.tv

import com.vayunmathur.cast.tv.platform.ReceiverFailure
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.platform.receiverRouteFor
import com.vayunmathur.library.util.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The receiver's destinations and their projection from the engine's
 * phases. [ReceiverPhase] stays the session state; [receiverRouteFor] is
 * the only place that turns it into a screen.
 */
class RouteTest {

    @Test
    fun `every phase projects to its destination`() {
        assertEquals(Route.Starting, receiverRouteFor(ReceiverPhase.Starting))
        assertEquals(Route.Ready, receiverRouteFor(ReceiverPhase.Advertising))
        assertEquals(
            Route.Pairing("Pixel 9", "123456", attemptsLeft = 2, codeChanged = true),
            receiverRouteFor(ReceiverPhase.Pairing("Pixel 9", "123456", 2, codeChanged = true)),
        )
        assertEquals(Route.Connected("Pixel 9"), receiverRouteFor(ReceiverPhase.Connected("Pixel 9")))
        assertEquals(
            Route.Failed(ReceiverFailure.NoDecoder),
            receiverRouteFor(ReceiverPhase.Failed(ReceiverFailure.NoDecoder)),
        )
    }

    @Test
    fun `mirroring keeps the connected destination naming the source`() {
        // The picture itself is MirrorActivity; underneath it the stack
        // holds Connected, naming the app when one is casting.
        assertEquals(
            Route.Connected("YouPipe"),
            receiverRouteFor(ReceiverPhase.Mirroring("Pixel 9", 1920, 1080, appLabel = "YouPipe")),
        )
        assertEquals(
            Route.Connected("Pixel 9"),
            receiverRouteFor(ReceiverPhase.Mirroring("Pixel 9", 1920, 1080)),
        )
    }

    @Test
    fun `pairing route defaults to an unchanged code`() {
        assertEquals(
            ReceiverPhase.Pairing("Pixel 9", "123456", 3).codeChanged,
            Route.Pairing("Pixel 9", "123456", 3).codeChanged,
        )
    }

    @Test
    fun `every route is a serializable nav key`() {
        val routes: List<Route> = listOf(
            Route.Starting,
            Route.Ready,
            Route.Pairing("Pixel 9", "123456", 3),
            Route.Connected("Pixel 9"),
            Route.Failed(ReceiverFailure.Handshake),
        )
        routes.forEach { assertIs<NavKey>(it) }
        // The stack persists destinations by kotlinx serialization, so
        // every destination must round-trip (this module's test classpath
        // has no kotlin-reflect for a sealed-subclass walk).
        val json = kotlinx.serialization.json.Json
        for (route in routes) {
            val text = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), text))
        }
    }
}
