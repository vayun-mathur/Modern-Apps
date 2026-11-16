package com.vayunmathur.games.kart

import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.sdk.controller.LobbyState
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The race screen is three destinations, not a phase switch:
 * [syncKartRoute] projects the engine's [RacePhase] onto the stack.
 * Countdown stays inside Race, and Results replaces Race so Back from
 * the results returns to the lobby.
 */
@RunWith(RobolectricTestRunner::class)
class KartRouteSyncTest {

    private val lobby = RacePhase.Lobby(LobbyState(gameId = "kart"))
    private val racing = RacePhase.Racing(
        order = listOf("local"),
        lapProgress = mapOf("local" to 0.5f),
        playerLap = 1,
        totalLaps = 3,
    )
    private val results = RacePhase.Results(winner = "local", order = listOf("local"))

    @Test
    fun `countdown and racing both show the race destination over the lobby`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Lobby))
        syncKartRoute(stack, RacePhase.Countdown(3))
        assertEquals(listOf(Route.Lobby, Route.Race), stack.backStack)
        syncKartRoute(stack, RacePhase.Countdown(1))
        syncKartRoute(stack, racing)
        assertEquals(listOf(Route.Lobby, Route.Race), stack.backStack)
    }

    @Test
    fun `results replaces the race so back returns to the lobby`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Lobby, Route.Race))
        syncKartRoute(stack, results)
        assertEquals(listOf(Route.Lobby, Route.Results), stack.backStack)
        stack.pop()
        assertEquals(listOf(Route.Lobby), stack.backStack)
    }

    @Test
    fun `returning to the lobby resets the stack and stays idempotent`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Lobby, Route.Results))
        syncKartRoute(stack, lobby)
        syncKartRoute(stack, lobby)
        assertEquals(listOf(Route.Lobby), stack.backStack)
    }
}
