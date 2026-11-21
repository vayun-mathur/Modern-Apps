package com.vayunmathur.games.kart.platform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.kart.data.KartDataStore
import com.vayunmathur.games.kart.data.KartProgress
import com.vayunmathur.games.kart.domain.GameInputLoop
import com.vayunmathur.games.kart.domain.KartRules
import com.vayunmathur.games.kart.domain.LapTracker
import com.vayunmathur.games.kart.domain.PlaceholderRace
import com.vayunmathur.games.kart.domain.RumbleDecider
import com.vayunmathur.games.kart.util.KartNative
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.sdk.controller.RumbleFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the garage screen and the progress behind it.
 *
 * A single cup pointer: advancing writes `cup + 1` and leaves the lifetime
 * counters alone, so there is no cup list and no way back.
 */
class KartViewModel(application: Application) : AndroidViewModel(application),
    KartGameActions {

    val dataStore = KartDataStore(application)

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json").bufferedReader().readText()
        KartAchievementsManager(application, json, dataStore)
    }

    private val _uiState = MutableStateFlow(KartUiState())
    val uiState: StateFlow<KartUiState> = _uiState.asStateFlow()

    /**
     * The TV lobby, backed by a real post-quantum identity.
     *
     * The identity loads off the main thread (keygen on first run touches the
     * native PQC library): until it lands, [KartHost] refuses handshakes
     * cleanly — the same posture the `{ null }` scaffold had, but only for
     * the cold-start window rather than forever. The device-key persistence
     * lives in [KartPairingStore] alongside the identity, outside the backup
     * set, so a remembered phone re-pairs silently across restarts.
     */
    private val pairingStore = KartPairingStore(application)

    @Volatile
    private var pqcIdentity: com.vayunmathur.e2ee.PqcIdentity? = null

    private val kartHost = KartHost(
        context = application,
        publicBundle = { pqcIdentity?.publicBundle },
        unsealSecret = { sealed ->
            val identity = pqcIdentity ?: return@KartHost null
            runCatching { identity.decrypt(sealed) }
                .getOrNull()
                ?.takeIf { it.size == com.vayunmathur.sdk.controller.ControllerKeys.SECRET_BYTES }
        },
        pairingStore = pairingStore,
    )

    private val _racePhase = MutableStateFlow<RacePhase>(RacePhase.Lobby(kartHost.lobby.value))
    /** Lobby → countdown → race → results. The garage reads this alongside [uiState]. */
    val racePhase: StateFlow<RacePhase> = _racePhase.asStateFlow()

    private var tickJob: Job? = null
    private var race: PlaceholderRace? = null

    /** This device's seat, so the HUD knows which kart is ours. Null until the race starts. */
    var localPlayerId: String? = null
        private set

    init {
        achievementsManager.checkExistingAchievements()

        // Load (or mint) the PQC identity off the main thread, then open the
        // lobby. First run pays keygen; every later start reads persisted keys.
        viewModelScope.launch {
            pqcIdentity = runCatching {
                com.vayunmathur.e2ee.PqcIdentity.loadOrCreate(pairingStore, prefix = "kartTv")
            }.getOrNull()
            kartHost.start()
        }

        viewModelScope.launch {
            kartHost.lobby.collectLatest { lobby ->
                if (_racePhase.value is RacePhase.Lobby) {
                    _racePhase.value = RacePhase.Lobby(lobby)
                }
            }
        }

        viewModelScope.launch {
            combine(
                dataStore.currentCup,
                dataStore.racesWon,
                dataStore.bestLapMs,
            ) { cup, won, bestLap ->
                Session(cup, won, bestLap)
            }.collectLatest { session -> load(session) }
        }
    }

    /** The stored inputs that decide which cup is on screen. */
    private data class Session(
        val cup: Int,
        val won: Int,
        val bestLap: Long,
    )

    private suspend fun load(session: Session) {
        _uiState.value = KartUiState(
            cup = KartRules.cupAt(session.cup),
            progress = KartProgress(
                currentCup = session.cup,
                racesWon = session.won,
                bestLapMs = session.bestLap,
            ),
        )
    }

    override fun startRace() {
        // The garage button starts the lobby countdown; the tick loop banks the cup clear when
        // the race finishes.
        if (_racePhase.value !is RacePhase.Lobby) return
        // Solo seat for the scaffold: this device drives kart one until phones join.
        localPlayerId = localPlayerId ?: "local-${System.currentTimeMillis()}"
        val seat = requireNotNull(localPlayerId)
        kartHost.host?.addLocalPlayer(seat, "You")
        viewModelScope.launch {
            for (count in COUNTDOWN_SECONDS downTo 1) {
                _racePhase.value = RacePhase.Countdown(count)
                delay(COUNTDOWN_STEP_MS)
            }
            beginRace(seat)
        }
    }

    /**
     * Feed one raw stick sample for the local seat. Called by the placeholder touch controls below;
     * gamepads and network phones arrive through the host instead.
     *
     * Builds the same [ControllerInput] shape every other source builds and routes it through the
     * same session, so the tick loop has exactly one input path per seat to read.
     */
    fun onLocalStick(playerId: String, x: Float, y: Float) {
        if (_racePhase.value !is RacePhase.Racing) return
        kartHost.host?.onLocalInput(
            com.vayunmathur.sdk.controller.ControllerInput(
                playerId = playerId,
                seq = 0L,
                axes = listOf(x, y, 0f, 0f, 0f, 0f),
            ),
        )
    }

    /** A gamepad took or freed a seat. Hot-plug mid-race changes the next race, never this one. */
    fun onGamepadSeat(playerId: String, name: String, present: Boolean) {
        if (present) kartHost.host?.addLocalPlayer(playerId, name.ifBlank { "Gamepad" })
        else kartHost.host?.removeLocalPlayer(playerId)
    }

    /** One gamepad sample, already shaped as a [ControllerInput] by [GamepadDirect]. */
    fun onGamepadInput(input: com.vayunmathur.sdk.controller.ControllerInput) {
        if (_racePhase.value !is RacePhase.Racing) return
        kartHost.host?.onLocalInput(input)
    }

    private fun beginRace(seat: String) {
        val playerIds = kartHost.lobby.value.slots.map { it.playerId }
        val seats = if (playerIds.isEmpty()) listOf(seat) else playerIds
        if (KartNative.isAvailable) {
            beginNativeRace(seat, seats)
        } else {
            beginPlaceholderRace(seat, seats)
        }
    }

    /**
     * The native race: the Rust engine owns the sim (seats, AI, items, laps) and ticks itself on
     * its own render thread; this loop only feeds inputs and polls standings for the HUD.
     */
    private fun beginNativeRace(seat: String, seats: List<String>) {
        KartNative.startRace(seats)
        kartHost.host?.startRace()
        val itemLatch = mutableMapOf<String, Boolean>()
        val localSeats = localPlayerIds()
        var prevOrder: List<String> = emptyList()
        val lastBuzzMs = mutableMapOf<String, Long>()
        var prevLap = 0
        var lapState = LapTracker.State(lastIncrementMs = android.os.SystemClock.uptimeMillis())
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                val latest = kartHost.inputs.value
                for (id in seats) {
                    val sample = latest[id] ?: continue
                    val control = GameInputLoop.normalize(sample)
                    val index = seats.indexOf(id)
                    KartNative.onDriveInput(index, control.throttle, control.steering, control.brake)
                    KartNative.setDrift(index, control.drift)
                    // Rising edge per seat: holding item fires once (the engine
                    // consumes the edge on its next step).
                    val fire = control.itemUse && itemLatch[id] != true
                    itemLatch[id] = control.itemUse
                    if (fire) KartNative.useItem(index)
                }
                val standings = parseStandings(KartNative.getStandingsJson())
                if (standings.isEmpty()) continue
                val ordered = standings.sortedBy { it.place }
                val order = ordered.map { it.player }
                // Fold the local lap in before the finish check so the final lap's
                // time can count as this race's best lap.
                val nowMs = android.os.SystemClock.uptimeMillis()
                val lap = ordered.firstOrNull { it.player == seat }?.lap ?: 0
                lapState = LapTracker.onLapTick(prevLap, lap, nowMs, lapState)
                prevLap = lap
                if (standings.any { it.lap >= RACE_LAPS }) {
                    // Standings sort by place ascending, so place 1 heads the order.
                    finishRace(
                        winner = ordered.first().player,
                        order = order,
                        bestLapMs = lapState.times.bestLapMs,
                    )
                    return@launch
                }
                buzzPlaceLosers(prevOrder, order, localSeats, lastBuzzMs)
                prevOrder = order
                _racePhase.value = RacePhase.Racing(
                    order = order,
                    lapProgress = ordered.associate { it.player to it.progress },
                    playerLap = lap,
                    totalLaps = RACE_LAPS,
                    lastLapMs = lapState.times.lastLapMs,
                    bestLapMs = lapState.times.bestLapMs,
                )
            }
        }
    }

    private fun beginPlaceholderRace(seat: String, seats: List<String>) {
        val newRace = PlaceholderRace(playerIds = seats)
        race = newRace
        kartHost.host?.startRace()
        val localSeats = localPlayerIds()
        var prevOrder: List<String> = emptyList()
        val lastBuzzMs = mutableMapOf<String, Long>()
        var prevLap = 0
        var lapState = LapTracker.State(lastIncrementMs = android.os.SystemClock.uptimeMillis())
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            var lastMs = android.os.SystemClock.uptimeMillis()
            while (isActive) {
                delay(TICK_MS)
                val nowMs = android.os.SystemClock.uptimeMillis()
                val dt = ((nowMs - lastMs).coerceAtMost(250L)) / 1_000f
                lastMs = nowMs
                val latest = kartHost.inputs.value
                for (id in seats) {
                    val sample = latest[id] ?: continue
                    val control = GameInputLoop.normalize(sample)
                    newRace.setControl(id, control)
                    KartNative.onDriveInput(
                        seats.indexOf(id),
                        control.throttle,
                        control.steering,
                        control.brake,
                    )
                    KartNative.setDrift(seats.indexOf(id), control.drift)
                    if (control.itemUse) {
                        newRace.useItem(id)
                        KartNative.useItem(seats.indexOf(id))
                    }
                }
                newRace.tick(dt)
                // Same fold-before-finish as the native loop, so both loops hand
                // finishRace the same shape (the gate there drops placeholder stats).
                val lap = newRace.lapOf(seat)
                lapState = LapTracker.onLapTick(prevLap, lap, nowMs, lapState)
                prevLap = lap
                if (newRace.finished) {
                    // The leaderboard sorts by progress descending, so its head won.
                    val order = newRace.leaderboard()
                    finishRace(
                        winner = newRace.winner() ?: order.first(),
                        order = order,
                        bestLapMs = lapState.times.bestLapMs,
                    )
                    return@launch
                }
                val order = newRace.leaderboard()
                buzzPlaceLosers(prevOrder, order, localSeats, lastBuzzMs)
                prevOrder = order
                _racePhase.value = RacePhase.Racing(
                    order = order,
                    lapProgress = order.associateWith { newRace.lapProgress(it) },
                    playerLap = lap,
                    totalLaps = RACE_LAPS,
                    lastLapMs = lapState.times.lastLapMs,
                    bestLapMs = lapState.times.bestLapMs,
                )
            }
        }
    }
    private fun finishRace(winner: String, order: List<String>, bestLapMs: Long?) {
        tickJob?.cancel()
        tickJob = null
        // Celebration buzz for every remote phone still on the grid.
        celebrate(order)
        kartHost.host?.endRace()
        achievementsManager.onAchievementUnlocked("first_race")
        if (winner == localPlayerId) achievementsManager.onAchievementUnlocked("first_win")
        // Finishing banks the next cup below, so a finish is a cup completion.
        achievementsManager.onAchievementUnlocked("cup_complete")
        _racePhase.value = RacePhase.Results(winner = winner, order = order)
        val state = _uiState.value
        viewModelScope.launch {
            KartRules.nextCup(state.progress.currentCup)?.let { dataStore.saveCup(it) }
            bankRaceStats(winner, localPlayerId, bestLapMs)
        }
        race = null
    }

    /**
     * Bank the lifetime counters for one finished race.
     *
     * [KartNative.isAvailable] is the gate: without the native engine the finish came from
     * the placeholder model, whose laps are model ticks rather than real lap times, so a
     * placeholder race writes nothing (no win, no best lap). A win is 1st place for the
     * local seat (see [KartRules.isPlayerWin]); [bestLapMs] is the local player's best lap
     * this race from `LapTracker`, already folded to include the final lap.
     */
    internal suspend fun bankRaceStats(winner: String, localPlayerId: String?, bestLapMs: Long?) {
        if (!KartNative.isAvailable) return
        dataStore.recordRaceResult(
            won = KartRules.isPlayerWin(winner, localPlayerId),
            bestLapMs = bestLapMs,
        )
    }

    /**
     * Buzz the remote phones whose place worsened since the last tick. Local seats (this device
     * plus gamepads) and AI karts are skipped before the host — its send no-ops for non-remote
     * slots anyway, but there is no reason to ask. Callers thread [prevOrder] through ticks and
     * let this update [lastBuzzMs] with who buzzed when.
     */
    private fun buzzPlaceLosers(
        prevOrder: List<String>,
        order: List<String>,
        localSeats: Set<String>,
        lastBuzzMs: MutableMap<String, Long>,
    ) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        val buzz = RumbleDecider.decide(
            prevOrder = prevOrder,
            newOrder = order,
            localPlayerIds = localSeats,
            lastBuzzMs = lastBuzzMs,
            nowMs = nowMs,
        )
        for (id in buzz) {
            kartHost.host?.rumble(id, RumbleFeedback(durationMs = RUMBLE_HIT_MS, highFreq = 1f))
            lastBuzzMs[id] = nowMs
        }
    }

    /** One short buzz to every remote phone on the grid, so the finish lands on all of them. */
    private fun celebrate(order: List<String>) {
        val locals = localPlayerIds()
        for (id in order) {
            if (id in locals || id.startsWith("ai-")) continue
            kartHost.host?.rumble(id, RumbleFeedback(durationMs = RUMBLE_FINISH_MS, highFreq = 1f))
        }
    }

    /** Every seat driven from this device: the touch seat plus any plugged-in gamepads. */
    private fun localPlayerIds(): Set<String> =
        kartHost.lobby.value.slots.filter { !it.remote }.map { it.playerId }.toSet()

    /**
     * One native standings row. Parsed by hand (not kotlinx.serialization): the engine emits a
     * fixed tiny shape and the game must never crash the tick on a malformed frame.
     */
    private data class NativeStanding(
        val player: String,
        val lap: Int,
        val progress: Float,
        val place: Int,
    )

    private fun parseStandings(json: String): List<NativeStanding> {
        val text = json.trim()
        if (!text.startsWith("[") || !text.endsWith("]")) return emptyList()
        val rows = text.removePrefix("[").removeSuffix("]").split("},")
        if (rows.size == 1 && rows[0].isBlank()) return emptyList()
        return rows.mapNotNull { row ->
            val obj = row.trim().removeSuffix("}").removePrefix("{")
            fun field(name: String): String? {
                val key = "\"$name\":"
                val at = obj.indexOf(key)
                if (at < 0) return null
                val rest = obj.substring(at + key.length).trim()
                return if (rest.startsWith("\"")) {
                    rest.removePrefix("\"").substringBefore("\"")
                } else {
                    rest.substringBefore(",").trim()
                }
            }
            val player = field("player") ?: return@mapNotNull null
            val lap = field("lap")?.toIntOrNull() ?: return@mapNotNull null
            val progress = field("progress")?.toFloatOrNull() ?: return@mapNotNull null
            val place = field("place")?.toIntOrNull() ?: return@mapNotNull null
            NativeStanding(player, lap, progress, place)
        }
    }

    /** Back to the lobby from results. Seats stay; the next Start Race counts down again. */
    override fun backToLobby() {
        _racePhase.value = RacePhase.Lobby(kartHost.lobby.value)
    }

    override fun onCleared() {
        tickJob?.cancel()
        kartHost.stop()
    }

    /** Shared constants and helpers for KartViewModel. */
    companion object {
        /** Ticks per second for the placeholder race loop. */
        private const val TICK_MS = 50L

        /** Buzz length for a place-loss hit. */
        private const val RUMBLE_HIT_MS = 180

        /** Buzz length for the finish-line celebration. */
        private const val RUMBLE_FINISH_MS = 250

        /** Laps per race. Mirrors the placeholder's default. */
        private const val RACE_LAPS = 3

        /** Seconds of 3-2-1 before controls go live. */
        private const val COUNTDOWN_SECONDS = 3

        /** One countdown tick: a single second. */
        private const val COUNTDOWN_STEP_MS = 1_000L
    }
}
