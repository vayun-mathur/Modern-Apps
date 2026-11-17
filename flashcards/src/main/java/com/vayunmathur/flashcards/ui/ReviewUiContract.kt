package com.vayunmathur.flashcards.ui
import com.vayunmathur.flashcards.domain.Grade
import com.vayunmathur.flashcards.platform.FlashcardsViewModel

import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField

/**
 * The UI contract between [FlashcardsViewModel] plus the nav back stack and the screens.
 *
 * Screens take a state value and an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. The binders in `ui` implement these interfaces. Every actions
 * method has a no-op default so a preview can use the `Noop` implementation with no VM.
 */


// ---------------------------------------------------------------------------

/** The kind of study session to build (custom study / cram). */
enum class StudyMode {
    /** Normal: due review cards + capped new cards. */
    DUE,

    /** Include review cards due within [StudyParams.daysAhead] days. */
    AHEAD,

    /** Only cards that have lapsed (relearning or lapses > 0). */
    LAPSES,

    /** Only new cards. */
    NEW_ONLY,

    /** Ignore due dates; take [StudyParams.count] cards by position, no schedule writes. */
    CRAM,
}

/** Parameters for a custom study session. */
data class StudyParams(
    val mode: StudyMode = StudyMode.DUE,
    val count: Int = 20,
    val daysAhead: Int = 3,
) {
    /** True when grading must not persist scheduling changes to cards. */
    val previewOnly: Boolean get() = mode == StudyMode.CRAM
}

/** Everything a review session draws for the current card. */
data class ReviewUiState(
    val front: String = "",
    val back: String = "",
    /** Cards still queued in this session, including the one shown. */
    val remaining: Int = 0,
    /** True when the queue is empty, i.e. the session is complete. */
    val done: Boolean = false,
    val newCount: Int = 0,
    val learningCount: Int = 0,
    val reviewCount: Int = 0,
    val progress: Float = 0f,
    /** Predicted next-interval label per grade button (e.g. "10m", "4d"). */
    val intervalLabels: Map<Grade, String> = emptyMap(),
    val canUndo: Boolean = false,
    /** The field name a `{{type:Field}}` template expects, or null when not a type-in card. */
    val typeField: String? = null,
    /** The expected answer for a `{{type:Field}}` card, shown after reveal. */
    val typeAnswer: String? = null,
    /** When true, the front is read aloud automatically as each card appears. */
    val autoPlay: Boolean = false,
) {
    fun label(grade: Grade): String = intervalLabels[grade] ?: ""
}

/** Callbacks for a review session: grading, undo, suspension, and speech. */
interface ReviewActions {
    fun back() {}
    fun grade(grade: Grade) {}
    fun undo() {}
    fun suspend() {}
    fun speak(text: String) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: ReviewActions = object : ReviewActions {}
    }
}

// ---------------------------------------------------------------------------
// Statistics
// ---------------------------------------------------------------------------

/** A single deck choice (plus an "All decks" option with a null id) in the stats picker. */
data class DeckOption(val id: Long?, val name: String)

/** One day of review history. [epochDay] is days since the Unix epoch (local). */
data class DailyStat(val epochDay: Long, val count: Int)

/** Everything the statistics screen draws, including history, forecast, and retention. */
data class StatsUiState(
    val deckOptions: List<DeckOption> = emptyList(),
    val selectedDeckId: Long? = null,
    /** Chronological daily review counts covering roughly the last year. */
    val daily: List<DailyStat> = emptyList(),
    val totalReviews: Int = 0,
    val retentionPct: Int = 0,
    val streakDays: Int = 0,
    val matureCards: Int = 0,
    val totalCards: Int = 0,
    /** Cards becoming due per day over the next ~30 days. */
    val forecast: List<DailyStat> = emptyList(),
    /** Pass rate (0..1) bucketed by the elapsed interval at review time. */
    val retentionBuckets: List<Pair<String, Float>> = emptyList(),
    /** Daily review counts for the activity heatmap (~26 weeks). */
    val heatmap: List<DailyStat> = emptyList(),
)

/** Callbacks for the statistics screen. */
interface StatsActions {
    fun back() {}
    fun selectDeck(id: Long?) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: StatsActions = object : StatsActions {}
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

/** Theme-mode values stored in settings: system, light, or dark. */
object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

/** A named bundle of per-deck study settings, saved/applied from Settings. */
data class DeckPreset(
    val name: String,
    val newPerDay: Int,
    val maxReviews: Int,
    val desiredRetention: Double,
)

/** Everything the settings screen draws, including study limits and reminders. */
data class SettingsUiState(
    val desiredRetention: Double = 0.9,
    val newPerDay: Int = 20,
    val maxReviews: Int = 200,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0,
    val themeMode: Int = ThemeMode.SYSTEM,
    val autoPlay: Boolean = false,
    val presets: List<DeckPreset> = emptyList(),
    /** True when the deck has enough review history for FSRS optimization. */
    val canOptimize: Boolean = false,
)

/** Callbacks for the settings screen, including FSRS optimisation and exports. */
interface SettingsActions {
    fun back() {}
    fun setDesiredRetention(value: Double) {}
    fun setNewPerDay(value: Int) {}
    fun setMaxReviews(value: Int) {}
    fun setReminderEnabled(enabled: Boolean) {}
    fun setReminderTime(hour: Int, minute: Int) {}
    fun setThemeMode(mode: Int) {}
    fun setAutoPlay(enabled: Boolean) {}
    fun saveDeckPreset(name: String) {}
    fun applyDeckPreset(name: String) {}
    fun deleteDeckPreset(name: String) {}
    fun optimizeFsrs() {}
    fun manageNoteTypes() {}
    fun exportCollection() {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
