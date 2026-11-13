package com.vayunmathur.games.wordmaker.platform

import com.vayunmathur.games.wordmaker.data.CrosswordData
import com.vayunmathur.games.wordmaker.data.Difficulty
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.WheelSpacing

/**
 * The UI contract between [WordMakerViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` alongside the ViewModel so the dependency runs one way:
 * the screens depend on this contract, and the ViewModel implements it.
 */

/** Everything the crossword screen draws. */
data class WordGameUiState(
    val crosswordData: CrosswordData,
    val currentLevel: Int = 1,
    val foundWords: Set<String> = emptySet(),
    val bonusWords: Set<String> = emptySet(),
    val tapToSpell: Boolean = false,
    val wheelSpacing: WheelSpacing = WheelSpacing.DEFAULT,
    val revealedHints: Set<Pair<Int, Int>> = emptySet(),
    /** Epoch millis until which the hint button stays disabled; 0 means available now. */
    val hintCooldownEnd: Long = 0L,
    val gameMode: GameMode = GameMode.CASUAL,
    val competitiveScore: Int = 0,
    val competitiveLevelNumber: Int = 0,
    /** Epoch millis at which the competitive timer expires; 0 outside competitive play. */
    val competitiveDeadline: Long = 0L,
    /** Local epoch day the daily board belongs to. */
    val dailyDay: Long = 0L,
    val dailyStreak: Long = 0L,
)

/** Everything the between-levels competitive lobby draws. */
data class CompetitiveLobbyUiState(
    val gameMode: GameMode = GameMode.COMPETITIVE,
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val score: Int = 0,
    val result: CompetitiveResult? = null,
)

/**
 * Crossword screen callbacks. Every method has a no-op default so a preview can render a
 * board without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface WordGameActions {
    fun addFoundWord(word: String) {}
    fun saveLevel(level: Int) {}
    fun setGameMode(mode: GameMode) {}
    fun revealHint(crosswordData: CrosswordData, foundWords: Set<String>, revealedHints: Set<Pair<Int, Int>>) {}
    fun isInDictionary(word: String): Boolean = false
    fun getDefinition(word: String): List<String> = emptyList()
    fun onCompetitiveTimeout() {}

    /**
     * A solution word was just traced (and credited through [addFoundWord] in the same step;
     * its letter flight into the grid is cosmetic). Separate from [addFoundWord] because
     * achievements belong to the activity, not the ViewModel.
     */
    fun onSolutionWordFound(word: String) {}

    /** Records a bonus word and returns the player's new bonus total. */
    suspend fun addBonusWord(word: String): Int = 0

    companion object {
        val Noop: WordGameActions = object : WordGameActions {}
    }
}

/** Competitive lobby callbacks. Same no-op-default arrangement as [WordGameActions]. */
interface CompetitiveLobbyActions {
    fun setGameMode(mode: GameMode) {}
    fun setDifficulty(difficulty: Difficulty) {}
    fun loadNextCompetitiveLevel() {}

    companion object {
        val Noop: CompetitiveLobbyActions = object : CompetitiveLobbyActions {}
    }
}

/** What the settings screen draws. */
data class SettingsUiState(
    val tapToSpell: Boolean = false,
    val wheelSpacing: WheelSpacing = WheelSpacing.DEFAULT,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
)

/** Settings callbacks. Same no-op-default arrangement as [WordGameActions]. */
interface SettingsActions {
    fun setTapToSpell(enabled: Boolean) {}
    fun setWheelSpacing(spacing: WheelSpacing) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}

    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
