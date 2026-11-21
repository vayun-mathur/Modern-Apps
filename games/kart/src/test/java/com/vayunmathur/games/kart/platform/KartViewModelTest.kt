package com.vayunmathur.games.kart.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KartViewModelTest {

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun pump(ms: Long) {
        if (ms > 0) shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)
        else shadowOf(Looper.getMainLooper()).idle()
    }

    private fun await(desc: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(0)
            if (cond()) return
            Thread.sleep(10)
        }
        pump(0)
        assertTrue(cond(), "timed out waiting for: $desc")
    }

    @Test fun garageLoadsThenRaceRunsToResultsAndBack() {
        val vm = KartViewModel(app)
        await("cup loads") { vm.uiState.value.cup != null }
        assertEquals("Mushroom Cup", vm.uiState.value.cup?.name)
        assertEquals(0, vm.uiState.value.progress.currentCup)
        assertTrue(vm.racePhase.value is RacePhase.Lobby)

        // Inputs outside a race are dropped quietly; gamepad seats still register.
        vm.onLocalStick("nobody", 1f, -1f)
        vm.onGamepadInput(ControllerInput(playerId = "pad-1", seq = 1, buttons = ControllerButton.A.bit))
        vm.onGamepadSeat("pad-x", "", true)
        vm.onGamepadSeat("pad-x", "Pad", false)

        vm.startRace()
        await("countdown starts") { vm.racePhase.value is RacePhase.Countdown }
        // Starting again mid-countdown is a no-op.
        vm.startRace()
        assertTrue(vm.racePhase.value is RacePhase.Countdown)
        assertNotNull(vm.localPlayerId)

        pump(4_000)
        await("race starts") { vm.racePhase.value is RacePhase.Racing || vm.racePhase.value is RacePhase.Results }

        // Feed the local seat through the public stick path while racing (if still racing).
        if (vm.racePhase.value is RacePhase.Racing) {
            vm.onLocalStick(requireNotNull(vm.localPlayerId), 0f, -1f)
            vm.onGamepadInput(
                ControllerInput(
                    playerId = requireNotNull(vm.localPlayerId),
                    seq = 2,
                    buttons = ControllerButton.A.bit,
                    axes = listOf(0f, -1f, 0f, 0f, 0f, 0f),
                ),
            )
        }

        if (KartNative.isAvailable) {
            // The native engine owns the sim; the tick loop must at least publish a Racing
            // phase parsed from its standings JSON.
            pump(500)
            assertTrue(
                vm.racePhase.value is RacePhase.Racing || vm.racePhase.value is RacePhase.Results,
                "phase=${vm.racePhase.value}",
            )
        } else {
            // Placeholder: the AI field finishes a 3-lap race on virtual time even with
            // no human input, which banks the next cup and lands on Results.
            pump(240_000)
            await("results", 20_000) { vm.racePhase.value is RacePhase.Results }
            val results = vm.racePhase.value as RacePhase.Results
            assertTrue(results.winner in results.order)
            assertTrue(results.order.isNotEmpty())
            await("next cup banked") { vm.uiState.value.progress.currentCup == 1 }
            assertEquals("Flower Cup", vm.uiState.value.cup?.name)
            vm.backToLobby()
            assertTrue(vm.racePhase.value is RacePhase.Lobby)
        }
    }

    @Test fun placeholderRaceRunsToResultsAndBanksCup() {
        val wasAvailable = KartNative.isAvailable
        KartNative.isAvailable = false
        try {
            val vm = KartViewModel(app)
            await("cup loads") { vm.uiState.value.cup != null }
            val cupBefore = vm.uiState.value.progress.currentCup
            val wonBefore = vm.uiState.value.progress.racesWon
            val bestBefore = vm.uiState.value.progress.bestLapMs
            vm.startRace()
            pump(4_000)
            await("placeholder racing") { vm.racePhase.value is RacePhase.Racing }
            val racing = vm.racePhase.value as RacePhase.Racing
            assertEquals(3, racing.totalLaps)
            assertTrue(racing.order.isNotEmpty())
            // Drive the local seat flat-out through the public stick path.
            vm.onLocalStick(requireNotNull(vm.localPlayerId), 0f, -1f)
            // The AI field finishes a 3-lap race on virtual time even unaided.
            pump(300_000)
            await("results", 20_000) { vm.racePhase.value is RacePhase.Results }
            val results = vm.racePhase.value as RacePhase.Results
            assertTrue(results.winner in results.order)
            val expectedCup = com.vayunmathur.games.kart.domain.KartRules.nextCup(cupBefore) ?: cupBefore
            await("cup banked") { vm.uiState.value.progress.currentCup == expectedCup }
            // A placeholder race writes no lifetime stats, even though it banked the cup.
            assertEquals(wonBefore, vm.uiState.value.progress.racesWon)
            assertEquals(bestBefore, vm.uiState.value.progress.bestLapMs)
            vm.backToLobby()
            assertTrue(vm.racePhase.value is RacePhase.Lobby)
            // Starting from the lobby again works after results.
            vm.startRace()
            await("countdown again") { vm.racePhase.value is RacePhase.Countdown }
        } finally {
            KartNative.isAvailable = wasAvailable
        }
    }

    @Test fun secondViewModelSeesPersistedCupState() {
        val vm = KartViewModel(app)
        await("cup loads") { vm.uiState.value.cup != null }
        // Whatever cup a previous race banked, the garage mirrors the store exactly.
        val cupIndex = vm.uiState.value.progress.currentCup
        assertEquals(com.vayunmathur.games.kart.domain.KartRules.cupAt(cupIndex), vm.uiState.value.cup)
        // Lifetime counters mirror the store exactly (a sibling test may have banked wins).
        assertEquals(runBlocking { vm.dataStore.racesWon.first() }, vm.uiState.value.progress.racesWon)
        assertEquals(runBlocking { vm.dataStore.bestLapMs.first() }, vm.uiState.value.progress.bestLapMs)
    }

    @Test fun nativeRaceStatsBankWinAndBestLapOnlyWhenNative() {
        val wasAvailable = KartNative.isAvailable
        try {
            val vm = KartViewModel(app)
            await("cup loads") { vm.uiState.value.cup != null }
            val won0 = vm.uiState.value.progress.racesWon
            val best0 = vm.uiState.value.progress.bestLapMs
            val fast = if (best0 > 1_000L) best0 - 1_000L else 45_000L
            KartNative.isAvailable = true
            // Win for the local seat (1st in the standings): win + better lap bank.
            runBlocking { vm.bankRaceStats("me", "me", fast) }
            await("win banked") {
                vm.uiState.value.progress.racesWon == won0 + 1 &&
                    vm.uiState.value.progress.bestLapMs == fast
            }
            // Non-win with a worse lap: neither counter moves.
            runBlocking { vm.bankRaceStats("ai-0", "me", fast + 10_000L) }
            pump(500)
            assertEquals(won0 + 1, vm.uiState.value.progress.racesWon)
            assertEquals(fast, vm.uiState.value.progress.bestLapMs)
            // A better lap without a win still replaces the best.
            runBlocking { vm.bankRaceStats("ai-0", "me", fast - 1L) }
            await("better lap banked") { vm.uiState.value.progress.bestLapMs == fast - 1L }
            assertEquals(won0 + 1, vm.uiState.value.progress.racesWon)
            // Placeholder gate: the same win with a far faster lap writes nothing.
            KartNative.isAvailable = false
            runBlocking { vm.bankRaceStats("me", "me", 1_000L) }
            pump(500)
            assertEquals(won0 + 1, vm.uiState.value.progress.racesWon)
            assertEquals(fast - 1L, vm.uiState.value.progress.bestLapMs)
        } finally {
            KartNative.isAvailable = wasAvailable
        }
    }
}
