package com.vayunmathur.games.wordmaker.platform

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.games.wordmaker.R
import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.data.WheelSpacing
import com.vayunmathur.games.wordmaker.domain.CompetitiveLevelGenerator
import com.vayunmathur.games.wordmaker.domain.Dictionary
import com.vayunmathur.library.util.DailyChallengeStore
import com.vayunmathur.library.util.DailyStreakReporter
import com.vayunmathur.library.work.DailyPuzzleReminder
import com.vayunmathur.library.work.DailyReminderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Outcome of a finished competitive level, shown on the between-levels lobby. */
data class CompetitiveResult(val won: Boolean, val delta: Int)

class WordMakerViewModel(application: Application) : AndroidViewModel(application),
    WordGameActions, CompetitiveLobbyActions {

    val levelDataStore: LevelDataStore = LevelDataStore(application)

    private val dictionary = Dictionary()

    val currentLevel: StateFlow<Int> = levelDataStore.currentLevel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1)

    private val casualFoundWords: StateFlow<Set<String>> = levelDataStore.foundWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val casualBonusWords: StateFlow<Set<String>> = levelDataStore.bonusWords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val casualRevealedHints: StateFlow<Set<Pair<Int, Int>>> = levelDataStore.revealedHints
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tapToSpell: StateFlow<Boolean> = levelDataStore.tapToSpell
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wheelSpacing: StateFlow<WheelSpacing> = levelDataStore.wheelSpacing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WheelSpacing.DEFAULT)

    private val reminderSettings = DailyReminderSettings(application, DAILY_KEY_PREFIX)

    val reminderEnabled: StateFlow<Boolean> = reminderSettings.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val reminderMinutesOfDay: StateFlow<Long> = reminderSettings.minutesOfDay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DailyPuzzleReminder.DEFAULT_MINUTES_OF_DAY)

    private val _hintCooldownEnd = MutableStateFlow(0L)
    val hintCooldownEnd: StateFlow<Long> = _hintCooldownEnd.asStateFlow()

    // ---- Competitive mode state ----

    val gameMode: StateFlow<GameMode> = levelDataStore.gameMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameMode.CASUAL)

    val difficulty: StateFlow<Difficulty> = levelDataStore.difficulty
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Difficulty.MEDIUM)

    val competitiveScore: StateFlow<Int> = levelDataStore.competitiveScore
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @Volatile
    private var levelGenerator: CompetitiveLevelGenerator? = null

    private val _competitiveLevelNumber = MutableStateFlow(0)
    val competitiveLevelNumber: StateFlow<Int> = _competitiveLevelNumber.asStateFlow()

    /** True while a competitive level is being played; false on the between-levels lobby. */
    private val _competitiveActive = MutableStateFlow(false)
    val competitiveActive: StateFlow<Boolean> = _competitiveActive.asStateFlow()

    /** Result of the last finished competitive level (null before the first level of the session). */
    private val _competitiveResult = MutableStateFlow<CompetitiveResult?>(null)
    val competitiveResult: StateFlow<CompetitiveResult?> = _competitiveResult.asStateFlow()

    private val _competitiveCrossword = MutableStateFlow<CrosswordData?>(null)
    private val _competitiveFoundWords = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Bonus words found in competitive play. Competitive boards are generated on the
     * fly and never persisted, so their bonus words stay in memory: folding them into
     * the saved casual set would show them as already-found when the player returns
     * to their casual level, and the showing flow would likewise surface the casual
     * set mid-competition.
     */
    private val _competitiveBonusWords = MutableStateFlow<Set<String>>(emptySet())

    /** Epoch millis at which the current competitive level's timer expires (0 when inactive). */
    private val _competitiveDeadline = MutableStateFlow(0L)
    val competitiveDeadline: StateFlow<Long> = _competitiveDeadline.asStateFlow()

    private val _casualCrossword = MutableStateFlow<CrosswordData?>(null)

    // ---- Daily challenge state ----

    private val dailyStore = DailyChallengeStore(application, DAILY_KEY_PREFIX)

    private val _dailyDay = MutableStateFlow(dailyStore.todayEpochDay())
    val dailyDay: StateFlow<Long> = _dailyDay.asStateFlow()

    private val _dailyCrossword = MutableStateFlow<CrosswordData?>(null)

    val dailyStreak: StateFlow<Long> = dailyStore.currentStreak
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val dailyBestStreak: StateFlow<Long> = dailyStore.bestStreakFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    /** Saved daily sets, blanked whenever the stored day is not the day currently being played. */
    private fun <T> dailyScoped(saved: Flow<T>, empty: T): Flow<T> =
        combine(_dailyDay, levelDataStore.dailyDay, saved) { today, stored, value ->
            if (stored == today) value else empty
        }

    private val dailyFoundWords: StateFlow<Set<String>> =
        dailyScoped(levelDataStore.dailyFoundWords, emptySet<String>())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val dailyBonusWords: StateFlow<Set<String>> =
        dailyScoped(levelDataStore.dailyBonusWords, emptySet<String>())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val dailyRevealedHints: StateFlow<Set<Pair<Int, Int>>> =
        dailyScoped(levelDataStore.dailyRevealedHints, emptySet<Pair<Int, Int>>())
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val crosswordData: StateFlow<CrosswordData?> =
        combine(
            gameMode,
            _casualCrossword,
            _competitiveCrossword,
            _dailyCrossword,
        ) { mode, casual, competitive, daily ->
            when (mode) {
                GameMode.COMPETITIVE -> competitive
                GameMode.DAILY -> daily
                GameMode.CASUAL -> casual
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val foundWords: StateFlow<Set<String>> =
        combine(
            gameMode,
            casualFoundWords,
            _competitiveFoundWords,
            dailyFoundWords,
        ) { mode, casual, competitive, daily ->
            when (mode) {
                GameMode.COMPETITIVE -> competitive
                GameMode.DAILY -> daily
                GameMode.CASUAL -> casual
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val bonusWords: StateFlow<Set<String>> =
        combine(gameMode, casualBonusWords, _competitiveBonusWords, dailyBonusWords) { mode, casual, competitive, daily ->
            when (mode) {
                GameMode.COMPETITIVE -> competitive
                GameMode.DAILY -> daily
                GameMode.CASUAL -> casual
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val revealedHints: StateFlow<Set<Pair<Int, Int>>> =
        combine(gameMode, casualRevealedHints, dailyRevealedHints) { mode, casual, daily ->
            when (mode) {
                // Hints cannot be earned in competitive play (the button is hidden
                // there), so show none rather than stale cells from the casual board.
                GameMode.COMPETITIVE -> emptySet()
                GameMode.DAILY -> daily
                GameMode.CASUAL -> casual
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            dictionary.init(getApplication())
        }
        viewModelScope.launch(Dispatchers.Default) {
            levelGenerator = CompetitiveLevelGenerator.fromAssets(getApplication())
        }
        viewModelScope.launch {
            currentLevel.collectLatest { level -> loadCasualLevel(level) }
        }
        viewModelScope.launch {
            gameMode.collectLatest { mode -> if (mode == GameMode.DAILY) refreshDaily() }
        }
        // Auto-count words that become fully filled in by their crossing words, so the player
        // never has to retrace a word that is already complete on the board.
        viewModelScope.launch {
            combine(crosswordData, foundWords) { data, found -> data to found }
                .collect { (data, found) ->
                    if (data == null) return@collect
                    val incidental = data.incidentalWords(found)
                    if (incidental.isEmpty()) return@collect
                    when (gameMode.value) {
                        GameMode.COMPETITIVE ->
                            _competitiveFoundWords.value = _competitiveFoundWords.value + incidental
                        GameMode.DAILY -> incidental.forEach { levelDataStore.addDailyFoundWord(it) }
                        GameMode.CASUAL -> incidental.forEach { levelDataStore.addFoundWord(it) }
                    }
                }
        }
        // Competitive mode always opens on the lobby; the player starts each level manually.
    }

    /**
     * Rolls the daily over to today (discarding a previous day's progress) and generates the
     * board. Cheap to call repeatedly: the board is only regenerated when the day changes.
     */
    private suspend fun refreshDaily() {
        val today = dailyStore.todayEpochDay()
        if (_dailyDay.value == today && _dailyCrossword.value != null) return
        _dailyDay.value = today
        _dailyCrossword.value = null
        levelDataStore.ensureDailyDay(today)
        val ctx = getApplication<Application>()
        val data = withContext(Dispatchers.Default) { generateDailyLevel(ctx, today) }
        _dailyCrossword.value = data
        if (gameMode.value == GameMode.DAILY) {
            _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
        }
    }

    /**
     * The day's board. The offset keeps daily seeds clear of the small level-number seeds casual
     * mode uses, so the two never generate the same puzzle.
     */
    private fun generateDailyLevel(ctx: Context, day: Long): CrosswordData? {
        val generator = resolveGenerator(ctx)
        val seed = DAILY_SEED_OFFSET + day * DAY_SEED_SCALE
        var data = generator.generate(Random(seed))
        var attempt = 1
        while (data == null && attempt < GENERATION_ATTEMPTS) {
            data = generator.generate(Random(seed + attempt * SEED_RETRY_BUMP))
            attempt++
        }
        return data
    }

    /** Records today's daily as solved, extending the streak. Idempotent within a day. */
    fun onDailyWin() {
        viewModelScope.launch {
            val day = _dailyDay.value
            val streak = dailyStore.recordDayCompleted(day)
            DailyStreakReporter.report(getApplication(), "wordmaker", streak, day)
        }
    }

    private suspend fun loadCasualLevel(level: Int) {
        val ctx = getApplication<Application>()
        val data = withContext(Dispatchers.Default) {
            // Designed levels (1..MAX_DESIGNED_LEVEL) ship as assets; beyond that (and for any
            // missing asset) generate a board deterministically seeded by the level number, so the
            // same level always yields the same layout and saved progress stays valid.
            val asset = if (level <= MAX_DESIGNED_LEVEL) {
                CrosswordData.fromAsset(ctx, "levels/$level.txt")
            } else null
            asset ?: generateSeededLevel(ctx, level)
        }
        _casualCrossword.value = data
        if (gameMode.value == GameMode.CASUAL) {
            _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
        }
    }

    /** Deterministically generates the board for a level beyond the designed set (seeded by level). */
    private fun generateSeededLevel(ctx: Context, level: Int): CrosswordData? {
        val generator = resolveGenerator(ctx)
        var data = generator.generate(Random(level.toLong()))
        var attempt = 1
        while (data == null && attempt < GENERATION_ATTEMPTS) {
            data = generator.generate(Random(level.toLong() + attempt * SEED_RETRY_BUMP))
            attempt++
        }
        return data
    }

    /** Cached on first use: loading the word list from assets is done once per process. */
    private fun resolveGenerator(ctx: Context): CompetitiveLevelGenerator {
        val existing = levelGenerator
        if (existing != null) return existing
        return CompetitiveLevelGenerator.fromAssets(ctx).also { levelGenerator = it }
    }

    /** Generates a fresh competitive layout on the fly, resets its found words and restarts the timer. */
    override fun loadNextCompetitiveLevel() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            _competitiveActive.value = true
            _competitiveResult.value = null
            // Show the loading spinner while the next board is generated.
            _competitiveCrossword.value = null
            _competitiveFoundWords.value = emptySet()
            _competitiveBonusWords.value = emptySet()
            val data = withContext(Dispatchers.Default) {
                resolveGenerator(ctx).generate()
            }
            _competitiveCrossword.value = data
            if (gameMode.value == GameMode.COMPETITIVE) {
                _error.value = if (data == null) ctx.getString(R.string.error_parse_level) else null
            }
            _competitiveLevelNumber.value = _competitiveLevelNumber.value + 1
            _competitiveDeadline.value =
                System.currentTimeMillis() + difficulty.value.timeLimitSeconds * MILLIS_PER_SECOND
        }
    }

    override fun setGameMode(mode: GameMode) {
        viewModelScope.launch {
            levelDataStore.setGameMode(mode)
            // Both modes return to a neutral state: competitive opens its lobby, casual resumes play.
            _competitiveActive.value = false
            _competitiveResult.value = null
            _competitiveDeadline.value = 0L
            // The error belongs to the mode that produced it, not to the one being entered.
            _error.value = null
        }
    }

    /**
     * Retries the load that failed for the current mode (the error screen's Retry button):
     * reloads the casual level, regenerates today's daily, or generates a new competitive board.
     */
    fun retryLoad() {
        _error.value = null
        when (gameMode.value) {
            GameMode.CASUAL -> viewModelScope.launch { loadCasualLevel(currentLevel.value) }
            GameMode.DAILY -> viewModelScope.launch { refreshDaily() }
            GameMode.COMPETITIVE -> loadNextCompetitiveLevel()
        }
    }

    override fun setDifficulty(difficulty: Difficulty) {
        // Difficulty is chosen on the lobby between levels, so it only needs to be persisted.
        viewModelScope.launch { levelDataStore.setDifficulty(difficulty) }
    }

    /** Player solved the competitive level in time: award points and return to the lobby. */
    fun onCompetitiveWin() {
        viewModelScope.launch {
            levelDataStore.addToCompetitiveScore(difficulty.value.winDelta)
            _competitiveResult.value = CompetitiveResult(won = true, delta = difficulty.value.winDelta)
            _competitiveDeadline.value = 0L
            _competitiveActive.value = false
        }
    }

    /** Timer ran out: deduct points and return to the lobby. */
    override fun onCompetitiveTimeout() {
        viewModelScope.launch {
            levelDataStore.addToCompetitiveScore(-difficulty.value.lossDelta)
            _competitiveResult.value = CompetitiveResult(won = false, delta = -difficulty.value.lossDelta)
            _competitiveDeadline.value = 0L
            _competitiveActive.value = false
        }
    }

    override fun isInDictionary(word: String): Boolean = word.lowercase() in dictionary

    override fun getDefinition(word: String): List<String> = dictionary.getDefinition(word)

    override fun saveLevel(level: Int) {
        viewModelScope.launch { levelDataStore.saveLevel(level) }
    }

    override fun addFoundWord(word: String) {
        when (gameMode.value) {
            GameMode.COMPETITIVE -> _competitiveFoundWords.value = _competitiveFoundWords.value + word
            GameMode.DAILY -> viewModelScope.launch { levelDataStore.addDailyFoundWord(word) }
            GameMode.CASUAL -> viewModelScope.launch { levelDataStore.addFoundWord(word) }
        }
    }

    override suspend fun addBonusWord(word: String): Int =
        when (gameMode.value) {
            GameMode.DAILY -> levelDataStore.addDailyBonusWord(word)
            // Competitive boards are unlimited generated layouts, so their bonus words
            // stay in memory and never inflate the lifetime bonus_hunter total.
            GameMode.COMPETITIVE -> {
                _competitiveBonusWords.value = _competitiveBonusWords.value + word
                levelDataStore.totalBonusWords.first()
            }
            GameMode.CASUAL -> levelDataStore.addBonusWord(word)
        }

    fun setTapToSpell(enabled: Boolean) {
        viewModelScope.launch { levelDataStore.setTapToSpell(enabled) }
    }

    fun setWheelSpacing(spacing: WheelSpacing) {
        viewModelScope.launch { levelDataStore.setWheelSpacing(spacing) }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            reminderSettings.setEnabled(enabled)
            rescheduleReminder(enabled, reminderMinutesOfDay.value)
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            val minutes = (hour * 60 + minute).toLong()
            reminderSettings.setMinutesOfDay(minutes)
            rescheduleReminder(reminderEnabled.value, minutes)
        }
    }

    private fun rescheduleReminder(enabled: Boolean, minutesOfDay: Long) {
        DailyPuzzleReminder.update(
            context = getApplication(),
            keyPrefix = DAILY_KEY_PREFIX,
            notificationId = REMINDER_NOTIFICATION_ID,
            enabled = enabled,
            hour = (minutesOfDay / MINUTES_PER_HOUR).toInt(),
            minute = (minutesOfDay % MINUTES_PER_HOUR).toInt(),
        )
    }

    override fun revealHint(crosswordData: CrosswordData, foundWords: Set<String>, revealedHints: Set<Pair<Int, Int>>) {
        val revealed = foundWords.flatMapTo(mutableSetOf()) { word ->
            crosswordData.letterPositions[word]?.flatten().orEmpty()
        } + revealedHints

        val allCells = crosswordData.letterPositions.values.flatMapTo(mutableSetOf()) { it.flatten() }
        val unrevealed = allCells - revealed
        if (unrevealed.isEmpty()) return

        val target = unrevealed.random()
        _hintCooldownEnd.value = System.currentTimeMillis() + HINT_COOLDOWN_MILLIS
        val daily = gameMode.value == GameMode.DAILY
        viewModelScope.launch {
            if (daily) {
                levelDataStore.addDailyRevealedHint(target.first, target.second)
            } else {
                levelDataStore.addRevealedHint(target.first, target.second)
            }
            val nowRevealed = revealed + target
            crosswordData.letterPositions.forEach { (word, occurrences) ->
                if (word !in foundWords && occurrences.any { nowRevealed.containsAll(it) }) {
                    if (daily) levelDataStore.addDailyFoundWord(word)
                    else levelDataStore.addFoundWord(word)
                }
            }
        }
    }

    companion object {
        /** Namespace shared by the daily-challenge store and its reminder. */
        const val DAILY_KEY_PREFIX = "wordmaker_daily"

        private const val REMINDER_NOTIFICATION_ID = 5101

        /** Highest level shipped as a designed asset; higher levels are generated at runtime. */
        const val MAX_DESIGNED_LEVEL = 8000

    /** Keeps date-derived daily seeds far away from the level-number seeds casual mode uses. */
        private const val DAILY_SEED_OFFSET = 700_000_000L

        private const val DAY_SEED_SCALE = 100
        private const val GENERATION_ATTEMPTS = 5
        private const val SEED_RETRY_BUMP = 1_000_000L
        private const val MILLIS_PER_SECOND = 1000L
        private const val MINUTES_PER_HOUR = 60
        private const val HINT_COOLDOWN_MILLIS = 30_000L
    }
}
