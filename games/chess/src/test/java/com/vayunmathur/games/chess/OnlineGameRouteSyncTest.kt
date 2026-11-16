package com.vayunmathur.games.chess

import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.platform.OnlineConnection
import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The live online game is a destination over the lobby:
 * [syncOnlineGameRoute] projects the ViewModel's Playing connection
 * onto the stack, carrying the game id, and pops back to the lobby
 * when the session leaves Playing.
 */
@RunWith(RobolectricTestRunner::class)
class OnlineGameRouteSyncTest {

    private fun playing(gameId: String) = OnlineConnection.Playing(
        gameId = gameId,
        localColor = PieceColor.WHITE,
        opponentName = "bob",
    )

    @Test
    fun `a live game pushes online game over the lobby`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Game, Route.Online))
        syncOnlineGameRoute(stack, playing("g1"))
        assertEquals(listOf(Route.Game, Route.Online, Route.OnlineGame("g1")), stack.backStack)
        // Idempotent while the same game stays live.
        syncOnlineGameRoute(stack, playing("g1"))
        assertEquals(listOf(Route.Game, Route.Online, Route.OnlineGame("g1")), stack.backStack)
    }

    @Test
    fun `leaving playing pops back to the lobby`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Online, Route.OnlineGame("g1")))
        syncOnlineGameRoute(stack, OnlineConnection.Idle)
        assertEquals(listOf<Route>(Route.Online), stack.backStack)
        // Idle in the lobby leaves the stack untouched.
        syncOnlineGameRoute(stack, OnlineConnection.Seeking(com.vayunmathur.games.chess.network.LichessTimeControl.BLITZ_3_2))
        assertEquals(listOf<Route>(Route.Online), stack.backStack)
    }

    @Test
    fun `a different game replaces the stale game destination`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Online, Route.OnlineGame("g1")))
        syncOnlineGameRoute(stack, playing("g2"))
        assertEquals(listOf(Route.Online, Route.OnlineGame("g2")), stack.backStack)
    }
}
