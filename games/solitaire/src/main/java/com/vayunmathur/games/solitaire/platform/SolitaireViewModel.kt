package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import com.vayunmathur.games.solitaire.data.Card
import com.vayunmathur.games.solitaire.data.CardColorScheme
import com.vayunmathur.games.solitaire.data.FreeCellState
import com.vayunmathur.games.solitaire.data.GameConfig
import com.vayunmathur.games.solitaire.data.GameMode
import com.vayunmathur.games.solitaire.data.GameStats
import com.vayunmathur.games.solitaire.data.KlondikeState
import com.vayunmathur.games.solitaire.data.PyramidState
import com.vayunmathur.games.solitaire.data.SolitaireUiState
import com.vayunmathur.games.solitaire.data.SolitaireStatsRepository
import com.vayunmathur.games.solitaire.data.SolitaireSettingsRepository
import com.vayunmathur.games.solitaire.data.SpiderState
import com.vayunmathur.library.util.AchievementsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The stack under the finger. [offset] is the leading card's current top-left and [startPos]
 * where it was picked up, both in root coordinates. The carried cards stay in game state at
 * [pileId] from [startIndex] until the drop, and the board hides them there so the only copy
 * on screen is the one the motion layer draws under the finger.
 */
data class DragInfo(
    val cards: List<Card>,
    val sourceId: String,
    val offset: Offset = Offset.Zero,
    val startPos: Offset = Offset.Zero,
    val cardSize: IntSize = IntSize.Zero,
    val pileId: String = "",
    val startIndex: Int = 0,
) {
    /** Where the carried cards live while they are carried. */
    val range: PileRange get() = PileRange(pileId, startIndex, cards)
}

class SolitaireViewModel(application: Application) : AndroidViewModel(application), SolitaireActions {
    internal val uiStateInternal = MutableStateFlow(SolitaireUiState())
    val uiState: StateFlow<SolitaireUiState> = uiStateInternal.asStateFlow()

    internal val dragInfoInternal = MutableStateFlow<DragInfo?>(null)
    override val dragInfo: StateFlow<DragInfo?> = dragInfoInternal.asStateFlow()

    internal val flightsInternal = MutableStateFlow<List<CardFlight>>(emptyList())
    override val flights: StateFlow<List<CardFlight>> = flightsInternal.asStateFlow()

    /** Ids for [flights]; never reused, so a late landing can never touch a newer flight. */
    internal var nextFlightId = 0L

    /** The auto-complete hop the run is waiting on, or null. */
    internal var autoCompleteFlightId: Long? = null

    /** Taps on cards that were still in the air, replayed as their flights land. */
    internal val queuedTaps = ArrayDeque<QueuedTap>()

    /** Card size last laid out, for flights launched without a gesture (auto-complete). */
    internal var lastCardSize: IntSize = IntSize.Zero

    internal val autoCompleteRunningInternal = MutableStateFlow(false)
    override val autoCompleteRunning: StateFlow<Boolean> = autoCompleteRunningInternal.asStateFlow()

    /**
     * Last laid-out root position per card, fed by the boards. Bounded by the 52
     * unique cards; entries from a previous deal are dropped on mode switch and
     * re-registered as piles lay out, so a stale entry never survives a new game.
     */
    internal val cardPositions = mutableMapOf<Card, Offset>()
    override fun registerCardPosition(card: Card, position: Offset, size: IntSize) {
        cardPositions[card] = position
        if (size.width > 0 && size.height > 0) lastCardSize = size
    }

    internal val statsRepository = SolitaireStatsRepository(application)
    internal val settingsRepository = SolitaireSettingsRepository(application)
    override val dropTargets: MutableMap<String, Rect> = mutableMapOf()

    private val _cardColorScheme = MutableStateFlow(settingsRepository.getCardColorScheme())
    val cardColorScheme: StateFlow<CardColorScheme> = _cardColorScheme.asStateFlow()

    fun setCardColorScheme(scheme: CardColorScheme) {
        settingsRepository.setCardColorScheme(scheme)
        _cardColorScheme.value = scheme
    }

    val achievementsManager: AchievementsManager = run {
        val json = application.assets.open("achievements.json")
            .bufferedReader().readText()
        SolitaireAchievementsManager(application, json, statsRepository)
    }

    init {
        achievementsManager.checkExistingAchievements()
    }

    fun getStats(mode: GameMode): GameStats = statsRepository.getModeStats(mode)

    fun hasActiveGame(): Boolean = with(uiStateInternal.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.isWon == false
            GameMode.SPIDER -> spider?.isWon == false
            GameMode.FREECELL -> freeCell?.isWon == false
            GameMode.PYRAMID -> pyramid?.isWon == false
            null -> false
        }
    }

    fun selectMode(mode: GameMode, config: GameConfig = GameConfig()) {
        clearMotion()
        cardPositions.clear()
        when (mode) {
            GameMode.KLONDIKE -> newKlondikeGame(config)
            GameMode.SPIDER -> newSpiderGame(config)
            GameMode.FREECELL -> newFreeCellGame()
            GameMode.PYRAMID -> newPyramidGame(config)
        }
    }

    /** The config of the currently active game, for restart / play-again. */
    fun currentConfig(): GameConfig = with(uiStateInternal.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.let {
                GameConfig(drawMode = it.drawMode, klondikeDifficulty = it.difficulty)
            }
            GameMode.SPIDER -> spider?.let { GameConfig(spiderSuits = it.suitCount) }
            GameMode.PYRAMID -> pyramid?.let { GameConfig(relaxed = it.relaxed) }
            else -> null
        } ?: GameConfig()
    }

    internal fun currentVariant(): String = with(uiStateInternal.value) {
        when (gameMode) {
            GameMode.KLONDIKE -> klondike?.variant
            GameMode.SPIDER -> spider?.variant
            GameMode.FREECELL -> freeCell?.variant
            GameMode.PYRAMID -> pyramid?.variant
            null -> null
        } ?: ""
    }

    override fun giveUp() {
        clearMotion()
        val mode = uiStateInternal.value.gameMode ?: return
        statsRepository.recordGameLost(mode, currentVariant())
        uiStateInternal.value = SolitaireUiState()
    }

    // --- Klondike ---
    // Public game operations live as extensions in SolitaireKlondikeOps.kt.

    override fun drawFromStock() {
        // Any tap mid-run skips the animation straight to the finished board.
        if (autoCompleteRunningInternal.value) { skipAutoCompleteToEnd(); return }
        drawFromStockImpl()
    }

    override fun klondikeAutoComplete() = requestAutoCompleteImpl()

    // --- Spider ---
    // Public game operations live as extensions in SolitaireSpiderOps.kt.

    override fun dealSpiderStock() = dealSpiderStockImpl()

    // --- FreeCell ---
    // Public game operations live as extensions in SolitaireFreeCellOps.kt.

    // --- Pyramid ---
    // Public game operations live as extensions in SolitairePyramidOps.kt.

    /**
     * Tap a card in the pyramid or on the waste. If it forms a valid pair with
     * the current selection (both exposed, or an exposed card plus a card it is
     * the sole cover of), both are removed. Kings (value 13) are removed on their
     * own. Otherwise an exposed tap becomes the new selection.
     */
    override fun pyramidTapCard(id: String) = pyramidTapCardImpl(id)

    override fun pyramidDealStock() = pyramidDealStockImpl()

    // --- Shared ---
    // Drag/drop/auto-move bodies live in SolitaireDragOps.kt; these keep the public/interface API.

    // --- Tap to move (auto) ---

    override fun autoMove(sourceId: String) = autoMoveImpl(sourceId)

    override fun requestAutoMove(sourceId: String, startPos: Offset, cardSize: IntSize): Boolean {
        if (autoCompleteRunningInternal.value) { skipAutoCompleteToEnd(); return false }
        return requestFlightImpl(sourceId, startPos, cardSize)
    }

    override fun landFlight(id: Long) = landFlightImpl(id)

    /**
     * Starts a drag from [sourceId], deriving the carried cards from current state.
     * Returns false (and starts nothing) when that source holds no card.
     */
    override fun startDrag(
        sourceId: String,
        startPos: Offset,
        cardSize: IntSize,
    ): Boolean {
        if (autoCompleteRunningInternal.value) { skipAutoCompleteToEnd(); return false }
        return startDragImpl(sourceId, startPos, cardSize)
    }

    override fun updateDrag(offset: Offset) = updateDragImpl(offset)

    override fun endDrag(dropOffset: Offset, cardSize: IntSize) =
        endDragImpl(dropOffset, cardSize)

    override fun cancelDrag() = cancelDragImpl()

    override fun undo() {
        clearMotion()
        val history = uiStateInternal.value.history
        if (history.isEmpty()) return
        val prev = history.last()
        val newHistory = history.dropLast(1)
        when (prev) {
            is KlondikeState -> uiStateInternal.update {
                it.copy(klondike = prev.copy(usedUndo = true), history = newHistory)
            }
            is SpiderState -> uiStateInternal.update {
                it.copy(spider = prev.copy(usedUndo = true), history = newHistory)
            }
            is FreeCellState -> uiStateInternal.update {
                it.copy(freeCell = prev.copy(usedUndo = true), history = newHistory)
            }
            is PyramidState -> uiStateInternal.update {
                it.copy(pyramid = prev.copy(usedUndo = true), history = newHistory)
            }
        }
    }

    override fun restart() {
        val mode = uiStateInternal.value.gameMode ?: return
        selectMode(mode, currentConfig())
    }

    fun incrementTimer() {
        uiStateInternal.update { state ->
            when (state.gameMode) {
                GameMode.KLONDIKE -> state.copy(
                    klondike = state.klondike?.tick(),
                )
                GameMode.SPIDER -> state.copy(
                    spider = state.spider?.tick(),
                )
                GameMode.FREECELL -> state.copy(
                    freeCell = state.freeCell?.tick(),
                )
                GameMode.PYRAMID -> state.copy(
                    pyramid = state.pyramid?.tick(),
                )
                null -> state
            }
        }
    }

    fun dismissAchievementNotification() {
        achievementsManager.dismissNotification()
    }

    internal fun saveHistory() {
        val state = uiStateInternal.value
        val current: Any = when (state.gameMode) {
            GameMode.KLONDIKE -> state.klondike ?: return
            GameMode.SPIDER -> state.spider ?: return
            GameMode.FREECELL -> state.freeCell ?: return
            GameMode.PYRAMID -> state.pyramid ?: return
            null -> return
        }
        uiStateInternal.update { it.copy(history = it.history + current) }
    }

    internal fun onGameWon(mode: GameMode, timeSeconds: Int, usedUndo: Boolean) {
        statsRepository.recordGameWon(mode, currentVariant(), timeSeconds)
        achievementsManager.onAchievementUnlocked("first_win")
        when (mode) {
            GameMode.KLONDIKE -> {
                achievementsManager.onAchievementUnlocked("klondike_first")
                if (timeSeconds < SPEED_DEMON_SECONDS) {
                    achievementsManager.onAchievementUnlocked("speed_demon")
                }
            }
            GameMode.SPIDER -> achievementsManager.onAchievementUnlocked("spider_first")
            GameMode.FREECELL -> achievementsManager.onAchievementUnlocked("freecell_first")
            GameMode.PYRAMID -> achievementsManager.onAchievementUnlocked("pyramid_first")
        }
        if (!usedUndo) achievementsManager.onAchievementUnlocked("no_undo")
        val totalWins = statsRepository.getTotalGamesWon()
        achievementsManager.onProgressUpdated("wins_10", totalWins)
        achievementsManager.onProgressUpdated("wins_50", totalWins)
        achievementsManager.onProgressUpdated("win_streak_5", statsRepository.getBestWinStreak())
    }

    companion object {
        /** Klondike win under three minutes earns the speed achievement. */
        private const val SPEED_DEMON_SECONDS = 180
    }
}

