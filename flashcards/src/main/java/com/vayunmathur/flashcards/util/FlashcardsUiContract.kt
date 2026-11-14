package com.vayunmathur.flashcards.util

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
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the binders in `ui` implement these interfaces. Every actions
 * method has a no-op default so a preview can use the `Noop` implementation with no VM.
 */

// ---------------------------------------------------------------------------
// Deck list
// ---------------------------------------------------------------------------

/** A deck plus its derived review counts, as drawn on the deck list. */
data class DeckSummary(
    val deck: Deck,
    val dueCount: Int = 0,
    val newCount: Int = 0,
    val totalCount: Int = 0,
    /** Fraction of cards that have graduated to the review state (0..1). */
    val mastery: Float = 0f,
)

/** Everything the deck list draws. */
data class DeckListUiState(
    val decks: List<DeckSummary> = emptyList(),
)

/** Callbacks for the deck list: opening, adding, deleting, reviewing, and reordering decks. */
interface DeckListActions {
    fun openDeck(id: Long) {}
    fun addDeck(name: String) {}
    fun deleteDeck(deck: Deck) {}
    fun startReview(deckId: Long) {}
    fun reorder(decks: List<Deck>) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: DeckListActions = object : DeckListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note list
// ---------------------------------------------------------------------------

/** A note plus the number of cards it currently generates, drawn on the note list. */
data class NoteRow(
    val note: Note,
    val cardCount: Int = 1,
    /** True when every card of this note is suspended. */
    val suspended: Boolean = false,
)

/** Everything the note list draws for a single deck. */
data class NoteListUiState(
    val deckName: String = "",
    val notes: List<NoteRow> = emptyList(),
    val dueCount: Int = 0,
    /** Distinct tags across the deck's notes, for the filter row. */
    val tags: List<String> = emptyList(),
    /** Decks available as move-to targets (excludes the current deck). */
    val decks: List<DeckOption> = emptyList(),
)

/** Callbacks for a deck's note list, including bulk selection actions. */
interface NoteListActions {
    fun back() {}
    fun openNote(id: Long) {}
    fun addNote() {}
    fun deleteNote(note: Note) {}
    fun study(tags: Set<String>) {}
    fun customStudy(params: StudyParams, tags: Set<String>) {}
    fun reorder(notes: List<Note>) {}
    fun openStats() {}
    fun share() {}
    fun exportCsv() {}
    // Bulk (selection-mode) actions, operating on a set of note ids.
    fun deleteNotes(ids: List<Long>) {}
    fun moveNotes(ids: List<Long>, deckId: Long) {}
    fun addTag(ids: List<Long>, tag: String) {}
    fun removeTag(ids: List<Long>, tag: String) {}
    fun setSuspended(ids: List<Long>, suspended: Boolean) {}
    fun resetScheduling(ids: List<Long>) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteListActions = object : NoteListActions {}
    }
}

// ---------------------------------------------------------------------------
// Note editor
// ---------------------------------------------------------------------------

/** A note type and its ordered field names, used to drive the note editor's fields. */
data class NoteTypeConfig(
    val id: Long,
    val name: String,
    val fieldNames: List<String>,
)

/** Everything the note editor draws: the selected note type, its fields, and tags. */
data class NoteEditUiState(
    val initialNoteTypeId: Long = 0,
    val initialDeckId: Long = 0,
    /** Field values in the selected note type's field order. */
    val initialFieldValues: List<String> = emptyList(),
    val initialTags: String = "",
    val isNew: Boolean = true,
    val isSuspended: Boolean = false,
    val noteTypes: List<NoteTypeConfig> = emptyList(),
    val decks: List<DeckOption> = emptyList(),
)

/** Callbacks for the note editor: saving, deleting, suspension, and image insertion. */
interface NoteEditActions {
    fun back() {}
    fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {}
    fun deleteNote() {}
    fun setSuspended(suspended: Boolean) {}
    fun insertImage(fieldIndex: Int) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteEditActions = object : NoteEditActions {}
    }
}

// ---------------------------------------------------------------------------
// Note type list + editor
// ---------------------------------------------------------------------------

/** A note type plus its counts, drawn on the note-type management list. */
data class NoteTypeSummary(
    val id: Long,
    val name: String,
    val fieldCount: Int,
    val templateCount: Int,
    val noteCount: Int,
    val isCloze: Boolean,
)

/** Everything the note-type management list draws. */
data class NoteTypeListUiState(
    val noteTypes: List<NoteTypeSummary> = emptyList(),
)

/** Callbacks for the note-type management list. */
interface NoteTypeListActions {
    fun back() {}
    fun openNoteType(id: Long) {}
    fun addNoteType() {}
    fun deleteNoteType(id: Long) {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteTypeListActions = object : NoteTypeListActions {}
    }
}

/** An editable template draft in the note-type editor. */
data class TemplateDraft(
    val name: String,
    val qfmt: String,
    val afmt: String,
)

/** Everything the note-type editor draws for one note type. */
data class NoteTypeEditUiState(
    val id: Long = 0,
    val name: String = "",
    val css: String = "",
    /** [com.vayunmathur.flashcards.data.NoteTypeKind]. Cloze note types have a fixed single template. */
    val type: Int = 0,
    val fields: List<String> = emptyList(),
    val templates: List<TemplateDraft> = emptyList(),
    val isNew: Boolean = true,
)

/** Callbacks for the note-type editor: saving and deleting the note type. */
interface NoteTypeEditActions {
    fun back() {}
    fun save(name: String, css: String, type: Int, fields: List<String>, templates: List<TemplateDraft>) {}
    fun delete() {}

    /** Provides the no-op implementation used by previews. */
    companion object {
        val Noop: NoteTypeEditActions = object : NoteTypeEditActions {}
    }
}

/** Bundles a note type with its ordered fields and templates for in-memory caching. */
data class NoteTypeWithConfig(
    val noteType: NoteType,
    val fields: List<NoteTypeField>,
    val templates: List<CardTemplate>,
)

// ---------------------------------------------------------------------------
// Review session
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
