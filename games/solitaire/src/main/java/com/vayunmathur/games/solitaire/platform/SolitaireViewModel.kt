package com.vayunmathur.games.solitaire.platform

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DragInfo(
    val cards: List<Card>,
    val sourceId: String,
    val offset: Offset = Offset.Zero,
    val startPos: Offset = Offset.Zero,
    val cardSize: IntSize = IntSize.Zero
)

/**
 * One animated card flight (tap-to-move or one autocomplete step): [cards]
 * travel from [from] to [to] (root coordinates) over [durationMillis].
 * The board hides the cards at the source for the duration and draws the
 * travelling copies in an overlay; the move itself is only applied once
 * the flight lands, so game state never shows an intermediate position.
 * [id] distinguishes consecutive flights that are otherwise equal.
 */
data class AnimatedMove(
    val id: Long,
    val cards: List<Card>,
    val sourceId: String,
    val targetId: String,
    val from: Rect,
    val to: Rect,
    val durationMillis: Int,
)

/**
 * Whether this flight hides the card rendered under [sourceId]: the card
 * at the flight's own source, plus — for a tableau stack — every card
 * below it in the same column, since the whole stack is travelling.
 */
fun AnimatedMove.hides(sourceId: String): Boolean {
    if (sourceId == this.sourceId) return true
    val flight = parseTableauSource(this.sourceId) ?: return false
    val candidate = parseTableauSource(sourceId) ?: return false
    return flight.first == candidate.first && candidate.second >= flight.second
}

private fun parseTableauSource(sourceId: String): Pair<Int, Int>? {
    if (!sourceId.startsWith("tableau_")) return null
    val parts = sourceId.removePrefix("tableau_").split("_")
    val column = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val index = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return column to index
}

class SolitaireViewModel(application: Application) : AndroidViewModel(application), SolitaireActions {
    internal val uiStateInternal = MutableStateFlow(SolitaireUiState())
    val uiState: StateFlow<SolitaireUiState> = uiStateInternal.asStateFlow()

    internal val dragInfoInternal = MutableStateFlow<DragInfo?>(null)
    override val dragInfo: StateFlow<DragInfo?> = dragInfoInternal.asStateFlow()

    internal val animatedMoveInternal = MutableStateFlow<AnimatedMove?>(null)
    override val animatedMove: StateFlow<AnimatedMove?> = animatedMoveInternal.asStateFlow()
    override val cardPositions: MutableMap<String, Rect> = mutableMapOf()

    private var animationJob: Job? = null
    private var animationSeq = 0L

    /** True while a tap-to-move flight or the autocomplete cascade runs. */
    internal fun isMoveAnimating(): Boolean = animationJob?.isActive == true

    internal fun nextAnimationId(): Long = ++animationSeq

    internal fun launchMoveAnimation(block: suspend () -> Unit) {
        animationJob = viewModelScope.launch { block() }
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
        val mode = uiStateInternal.value.gameMode ?: return
        statsRepository.recordGameLost(mode, currentVariant())
        uiStateInternal.value = SolitaireUiState()
    }

    // --- Klondike ---
    // Public game operations live as extensions in SolitaireKlondikeOps.kt.

    override fun drawFromStock() = drawFromStockImpl()

    override fun klondikeAutoComplete() = klondikeAutoCompleteImpl()

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

    override fun autoMoveAnimated(sourceId: String, from: Offset, cardSize: IntSize) =
        autoMoveAnimatedImpl(sourceId, from, cardSize)

    override fun klondikeAutoCompleteAnimated() = klondikeAutoCompleteAnimatedImpl()

    /**
     * Starts a drag from [sourceId], deriving the carried cards from current state.
     * Returns false (and starts nothing) when that source holds no card.
     */
    override fun startDrag(
        sourceId: String,
        startPos: Offset,
        cardSize: IntSize,
    ): Boolean = startDragImpl(sourceId, startPos, cardSize)

    override fun updateDrag(offset: Offset) = updateDragImpl(offset)

    override fun endDrag(dropOffset: Offset, cardSize: IntSize) =
        endDragImpl(dropOffset, cardSize)

    override fun cancelDrag() = cancelDragImpl()

    override fun undo() {
        // A flight in progress is about to apply its move against the
        // current state; restoring history underneath it would make the
        // landing unpredictable. Flights are short: ignore the tap.
        if (isMoveAnimating()) return
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

