package com.vayunmathur.games.kart.domain

import com.vayunmathur.games.kart.data.CUPS
import com.vayunmathur.sdk.controller.ControllerButton
import com.vayunmathur.sdk.controller.ControllerInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KartLogicExtraTest {

    private fun input(
        buttons: Long = 0L,
        axes: List<Float> = List(6) { 0f },
    ) = ControllerInput(playerId = "p1", seq = 1, buttons = buttons, axes = axes)

    // --- KartRules ---

    @Test fun cupAtWrapsPastEndAndNegative() {
        assertEquals(CUPS[0], KartRules.cupAt(0))
        assertEquals(CUPS[1], KartRules.cupAt(1))
        assertEquals(CUPS[0], KartRules.cupAt(CUPS.size))
        assertEquals(CUPS[CUPS.size - 1], KartRules.cupAt(-1))
    }

    @Test fun isUnlockedBoundary() {
        assertTrue(KartRules.isUnlocked(0, 0))
        assertTrue(KartRules.isUnlocked(2, 2))
        assertFalse(KartRules.isUnlocked(1, 0))
        assertFalse(KartRules.isUnlocked(3, 2))
    }

    @Test fun nextCupEndsAtLast() {
        assertEquals(1, KartRules.nextCup(0))
        assertEquals(CUPS.size - 1, KartRules.nextCup(CUPS.size - 2))
        assertNull(KartRules.nextCup(CUPS.size - 1))
        assertNull(KartRules.nextCup(CUPS.size + 5))
    }

    // --- LapTracker ---

    @Test fun lapTickWithoutSeedRecordsNoDurationButAdvances() {
        val state = LapTracker.onLapTick(prevLap = 0, lap = 1, nowMs = 5_000L, state = LapTracker.State())
        assertNull(state.times.lastLapMs)
        assertNull(state.times.bestLapMs)
        assertEquals(5_000L, state.lastIncrementMs)
    }

    @Test fun lapTickClampsNegativeDurationAndKeepsBest() {
        var state = LapTracker.State(lastIncrementMs = 10_000L, times = LapTimes(lastLapMs = 100L, bestLapMs = 50L))
        state = LapTracker.onLapTick(prevLap = 1, lap = 2, nowMs = 9_000L, state = state)
        assertEquals(0L, state.times.lastLapMs)
        assertEquals(0L, state.times.bestLapMs) // 0 beats previous best via coerceAtMost? best = min(50,0)=0
    }

    @Test fun lapTickSlowerLapDoesNotReplaceBest() {
        var state = LapTracker.State(lastIncrementMs = 0L)
        state = LapTracker.onLapTick(0, 1, 20_000L, state)
        state = LapTracker.onLapTick(1, 2, 50_000L, state)
        assertEquals(30_000L, state.times.lastLapMs)
        assertEquals(20_000L, state.times.bestLapMs)
    }

    @Test fun lapDecreaseKeepsState() {
        val state = LapTracker.State(lastIncrementMs = 1_000L, times = LapTimes(5L, 5L))
        assertEquals(state, LapTracker.onLapTick(prevLap = 3, lap = 1, nowMs = 9_000L, state = state))
    }

    // --- GameInputLoop ---

    @Test fun curveDeadzoneEdgeAndMid() {
        assertEquals(0f, GameInputLoop.curve(0.149f))
        assertEquals(0f, GameInputLoop.curve(GameInputLoop.DEADZONE)) // t=0 at edge
        val mid = GameInputLoop.curve(0.5f)
        assertTrue(mid > 0f && mid < 0.5f, "mid=$mid")
        assertEquals(-mid, GameInputLoop.curve(-0.5f))
        assertEquals(1f, GameInputLoop.curve(2f)) // coerced rescale caps at full throw shaped value? t coerced 1 -> 1
    }

    @Test fun triggerAxesBeatStickAndBrakeAxisWorks() {
        // axes: LX, LY, RX, RY, throttle(trigger), brake(trigger)
        val c = GameInputLoop.normalize(input(axes = listOf(0f, 0f, 0f, 0f, 1f, 0.5f)))
        assertEquals(1f, c.throttle)
        assertTrue(c.brake > 0f)
    }

    @Test fun shortAxesListDefaultsToNeutral() {
        val c = GameInputLoop.normalize(input(axes = emptyList()))
        assertEquals(KartControl.NEUTRAL, c)
    }

    @Test fun leftShoulderAlsoDriftsAndBIsNotItem() {
        val drift = GameInputLoop.normalize(input(buttons = ControllerButton.LEFT_SHOULDER.bit))
        assertTrue(drift.drift)
        assertFalse(drift.itemUse)
        val b = GameInputLoop.normalize(input(buttons = ControllerButton.B.bit))
        assertFalse(b.itemUse)
        assertFalse(b.drift)
    }

    @Test fun dpadRightSteersAndStickOverridesDpad() {
        val dpad = GameInputLoop.normalize(input(buttons = ControllerButton.DPAD_RIGHT.bit))
        assertEquals(0.6f, dpad.steering)
        val stick = GameInputLoop.normalize(
            input(buttons = ControllerButton.DPAD_RIGHT.bit, axes = listOf(1f, 0f, 0f, 0f, 0f, 0f)),
        )
        assertEquals(1f, stick.steering)
    }

    @Test fun outputsAreCoerced() {
        val c = GameInputLoop.normalize(input(axes = listOf(5f, -5f, 0f, 0f, 5f, 0f)))
        assertTrue(c.steering in -1f..1f)
        assertTrue(c.throttle in 0f..1f)
        assertTrue(c.brake in 0f..1f)
    }

    // --- PlaceholderRace edges ---

    @Test fun unknownPlayerQueriesAreZeroAndWinnerNullBeforeFinish() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        assertEquals(0, race.lapOf("nobody"))
        assertEquals(0f, race.lapProgress("nobody"))
        assertNull(race.winner())
        assertFalse(race.finished)
        assertFalse(race.useItem("nobody"))
    }

    @Test fun brakeCanStopButNeverReverses() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        race.setControl("p1", KartControl(throttle = 0f, brake = 1f))
        race.tick(1f)
        assertEquals(0f, race.lapProgress("p1"))
    }

    @Test fun negativeAndHugeDtAreClamped() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 0)
        race.setControl("p1", KartControl(throttle = 1f))
        race.tick(-5f)
        assertEquals(0f, race.lapProgress("p1"))
        race.tick(100f) // clamped to 1s
        assertEquals(PlaceholderRace.TOP_SPEED / PlaceholderRace.TRACK_LENGTH, race.lapProgress("p1"), 0.0001f)
    }

    @Test fun leaderboardIncludesAiAndCupIndexWraps() {
        val race = PlaceholderRace(playerIds = listOf("p1"), aiCount = 2)
        val board = race.leaderboard()
        assertEquals(3, board.size)
        assertTrue("ai-0" in board && "ai-1" in board)
        assertEquals(0, race.cupIndex(0))
        assertEquals(0, race.cupIndex(CUPS.size))
        assertEquals(CUPS.size - 1, race.cupIndex(-1))
    }

    @Test fun finishedKartStopsAdvancingWhileOthersContinue() {
        val race = PlaceholderRace(playerIds = listOf("fast", "slow"), aiCount = 0, laps = 1)
        race.setControl("fast", KartControl(throttle = 1f))
        race.setControl("slow", KartControl(throttle = 0.1f))
        repeat(30) { race.tick(1f) }
        assertTrue(race.finished)
        val fastProgress = race.lapProgress("fast")
        repeat(5) { race.tick(1f) }
        // lapProgress wraps, so compare lap counts instead: fast stays at >=1 lap
        assertTrue(race.lapOf("fast") >= 1)
        assertEquals(fastProgress, race.lapProgress("fast"))
    }

    // --- RumbleDecider edges ---

    @Test fun customCooldownRespected() {
        val buzz = RumbleDecider.decide(
            prevOrder = listOf("a-remote", "you"),
            newOrder = listOf("you", "a-remote"),
            localPlayerIds = setOf("you"),
            lastBuzzMs = mapOf("a-remote" to 1_000L),
            nowMs = 1_500L,
            cooldownMs = 100L,
        )
        assertEquals(listOf("a-remote"), buzz)
    }

    @Test fun multipleLosersBuzzInNewOrder() {
        val buzz = RumbleDecider.decide(
            prevOrder = listOf("r1", "r2", "you"),
            newOrder = listOf("you", "r2", "r1"),
            localPlayerIds = setOf("you"),
            lastBuzzMs = emptyMap(),
            nowMs = 0L,
        )
        // r2: place 1->1? prev index1, new index1 => no. r1: 0->2 yes
        assertEquals(listOf("r1"), buzz)
    }
}
