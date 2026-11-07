package com.vayunmathur.games.kart.platform

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput
import java.util.concurrent.TimeUnit
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
        assertEquals(0, vm.uiState.value.progress.racesWon)
    }
}
