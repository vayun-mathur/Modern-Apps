package com.vayunmathur.games.wordmaker

import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.library.util.NavBackStack
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The competitive lobby is a destination, not a loader arm:
 * [syncWordMakerRoute] swaps board and lobby with a reset (they are
 * alternatives for the same slot), and leaves destinations pushed over
 * either untouched.
 */
@RunWith(RobolectricTestRunner::class)
class WordMakerRouteSyncTest {

    @Test
    fun `competitive without an active level shows the lobby`() {
        val stack = NavBackStack(arrayOf<Route>(Route.Game))
        syncWordMakerRoute(stack, GameMode.COMPETITIVE, competitiveActive = false)
        assertEquals(listOf<Route>(Route.CompetitiveLobby), stack.backStack)
    }

    @Test
    fun `starting a competitive level swaps back to the board`() {
        val stack = NavBackStack(arrayOf<Route>(Route.CompetitiveLobby))
        syncWordMakerRoute(stack, GameMode.COMPETITIVE, competitiveActive = true)
        assertEquals(listOf<Route>(Route.Game), stack.backStack)
    }

    @Test
    fun `casual and daily always show the board`() {
        val stack = NavBackStack(arrayOf<Route>(Route.CompetitiveLobby))
        syncWordMakerRoute(stack, GameMode.CASUAL, competitiveActive = false)
        assertEquals(listOf<Route>(Route.Game), stack.backStack)
        syncWordMakerRoute(stack, GameMode.DAILY, competitiveActive = false)
        assertEquals(listOf<Route>(Route.Game), stack.backStack)
    }

    @Test
    fun `destinations pushed over the board or lobby are left alone`() {
        val stack = NavBackStack(arrayOf<Route>(Route.CompetitiveLobby, Route.Settings))
        syncWordMakerRoute(stack, GameMode.CASUAL, competitiveActive = false)
        assertEquals(listOf(Route.CompetitiveLobby, Route.Settings), stack.backStack)
        stack.pop()
        syncWordMakerRoute(stack, GameMode.CASUAL, competitiveActive = false)
        assertEquals(listOf<Route>(Route.Game), stack.backStack)
    }
}
