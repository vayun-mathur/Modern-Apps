package com.vayunmathur.games.solitaire.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.solitaire.domain.SolitaireDeals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SolitaireProgressCodecTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun midGameKlondike(): SolitaireUiState {
        val dealt = SolitaireDeals.klondike(12345L, GameConfig())
        // One stock card drawn to the waste: a real mid-game position on the same seed.
        val mid = dealt.copy(
            stock = dealt.stock.drop(1),
            waste = dealt.waste + dealt.stock.first(),
            moveCount = 1,
            elapsedSeconds = 33,
        )
        return SolitaireUiState(
            gameMode = GameMode.KLONDIKE,
            klondike = mid,
            history = listOf(dealt),
        )
    }

    @Test
    fun midGameKlondikeRoundTripsThroughCodec() {
        val state = midGameKlondike()
        val decoded = SolitaireProgressCodec.decode(
            SolitaireProgressCodec.encode(state),
            GameMode.KLONDIKE,
        )
        assertEquals(state, decoded)
        assertEquals(12345L, decoded!!.klondike!!.seed)
        assertEquals(1, decoded.history.size)
    }

    @Test
    fun midGameFreeCellRoundTripsThroughCodec() {
        val dealt = SolitaireDeals.freeCell(777L)
        val mid = dealt.copy(
            tableauPiles = dealt.tableauPiles.mapIndexed { i, pile ->
                if (i == 0) pile.drop(1) else pile
            },
            freeCells = dealt.freeCells.mapIndexed { i, c ->
                if (i == 0) dealt.tableauPiles[0].first() else c
            },
            moveCount = 1,
            elapsedSeconds = 9,
        )
        val state = SolitaireUiState(
            gameMode = GameMode.FREECELL,
            freeCell = mid,
            history = listOf(dealt),
        )
        assertEquals(
            state,
            SolitaireProgressCodec.decode(SolitaireProgressCodec.encode(state), GameMode.FREECELL),
        )
    }

    @Test
    fun wrongModeAndCorruptJsonAreDropped() {
        val encoded = SolitaireProgressCodec.encode(midGameKlondike())
        assertNull(SolitaireProgressCodec.decode(encoded, GameMode.SPIDER))
        assertNull(SolitaireProgressCodec.decode("not json", GameMode.KLONDIKE))
        assertNull(SolitaireProgressCodec.decode("""{"v":2}""", GameMode.KLONDIKE))
    }

    @Test
    fun midGameRoundTripsThroughStore() {
        val store = InProgressGame(app)
        store.clear(GameMode.KLONDIKE)
        val state = midGameKlondike()
        store.save(state)
        assertEquals(state, assertNotNull(InProgressGame(app).load(GameMode.KLONDIKE)))
        assertNull(store.load(GameMode.FREECELL))
        store.clear(GameMode.KLONDIKE)
        assertNull(store.load(GameMode.KLONDIKE))
    }
}
