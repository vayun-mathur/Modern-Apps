package com.vayunmathur.games.kart.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.R
import com.vayunmathur.games.kart.data.CUPS
import com.vayunmathur.games.kart.data.KartProgress
import com.vayunmathur.games.kart.platform.KartGameActions
import com.vayunmathur.games.kart.platform.KartUiState
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.sdk.controller.LobbyState
import com.vayunmathur.sdk.controller.PlayerSlot
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class KartGameScreenTest {

    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = ctx.getString(id, *args)

    private class RecordingActions : KartGameActions {
        var starts = 0
        var backs = 0
        override fun startRace() { starts++ }
        override fun backToLobby() { backs++ }
    }

    @Test fun lobbyLoadingWhenCupMissing() {
        rule.setContent {
            KartGameScreen(KartUiState(cup = null), RacePhase.Lobby(LobbyState(gameId = "kart")), KartGameActions.Noop)
        }
        rule.onNodeWithText(str(R.string.garage_loading)).assertIsDisplayed()
    }

    @Test fun lobbyShowsCupWinsSeatsAndStartFires() {
        val actions = RecordingActions()
        val lobby = LobbyState(
            gameId = "kart",
            slots = listOf(
                PlayerSlot("p1", "You", remote = false),
                PlayerSlot("p2", "Phone", remote = true),
            ),
        )
        rule.setContent {
            KartGameScreen(
                KartUiState(cup = CUPS[1], progress = KartProgress(currentCup = 1, racesWon = 3, bestLapMs = 10_000L)),
                RacePhase.Lobby(lobby),
                actions,
            )
        }
        rule.onNodeWithText(str(R.string.cup_title, "Flower Cup")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.cup_tracks, 4)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.races_won, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.lobby_seats, 2)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.how_to_play)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.start_race)).performClick()
        assertEquals(1, actions.starts)
        assertEquals(0, actions.backs)
    }

    @Test fun lobbyWithoutWinsOrSeatsHidesThoseRows() {
        rule.setContent {
            KartGameScreen(
                KartUiState(cup = CUPS[0], progress = KartProgress()),
                RacePhase.Lobby(LobbyState(gameId = "kart")),
                KartGameActions.Noop,
            )
        }
        rule.onNodeWithText(str(R.string.start_race)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.races_won, 0)).assertDoesNotExist()
    }

    @Test fun countdownShowsNumberAndHint() {
        rule.setContent {
            KartGameScreen(KartUiState(cup = CUPS[0]), RacePhase.Countdown(2), KartGameActions.Noop)
        }
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithText(str(R.string.countdown_hint)).assertIsDisplayed()
    }

    @Test fun racingShowsLapTimesAndStandings() {
        val phase = RacePhase.Racing(
            order = listOf("p1", "ai-0", "ai-12"),
            lapProgress = mapOf("p1" to 0.5f, "ai-0" to 0.25f),
            playerLap = 1,
            totalLaps = 3,
            lastLapMs = 83_456L,
            bestLapMs = 61_005L,
        )
        rule.setContent { KartGameScreen(KartUiState(cup = CUPS[2]), phase, KartGameActions.Noop) }
        rule.onNodeWithText(str(R.string.race_lap, 2, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_last_lap, "1:23.456")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_best_lap, "1:01.005")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_place, 1, "You")).assertExists()
        rule.onNodeWithText(str(R.string.race_place, 2, "Rival 0")).assertExists()
        rule.onNodeWithText(str(R.string.race_place, 3, "Rival 12")).assertExists()
    }

    @Test fun racingWithoutLapTimesHidesTimeRows() {
        val phase = RacePhase.Racing(
            order = listOf("p1"),
            lapProgress = emptyMap(),
            playerLap = 0,
            totalLaps = 3,
        )
        rule.setContent { KartGameScreen(KartUiState(cup = CUPS[0]), phase, KartGameActions.Noop) }
        rule.onNodeWithText(str(R.string.race_lap, 1, 3)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_place, 1, "You")).assertIsDisplayed()
    }

    @Test fun resultsShowsWinnerAndBackFires() {
        val actions = RecordingActions()
        val phase = RacePhase.Results(winner = "ai-1", order = listOf("ai-1", "p1"))
        rule.setContent { KartGameScreen(KartUiState(cup = CUPS[0]), phase, actions) }
        rule.onNodeWithText(str(R.string.results_title)).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_place, 1, "Rival 1")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.race_place, 2, "You")).assertIsDisplayed()
        rule.onNodeWithText(str(R.string.back_to_lobby)).performClick()
        assertEquals(1, actions.backs)
        assertEquals(0, actions.starts)
    }

    @Test fun noopActionsAreHarmless() {
        KartGameActions.Noop.startRace()
        KartGameActions.Noop.backToLobby()
    }
}
