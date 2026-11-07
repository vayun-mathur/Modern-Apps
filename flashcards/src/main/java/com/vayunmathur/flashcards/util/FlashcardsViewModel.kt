package com.vayunmathur.flashcards.util

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.ReviewLog
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ViewModel for the Flashcards app.
 *
 * Owns the deck/note/card [StateFlow]s collected by the screens, the cached
 * [noteTypes] (Anki-style models), the persisted [settings], and the in-memory
 * [ReviewSession] driving the review screen via [review]. Card content is rendered
 * on demand from a note + its template through [TemplateEngine]. Grading runs the
 * pure [Scheduler], upserts the card, and writes a [ReviewLog] row.
 *
 * Operations live as `internal` extensions in FlashcardsDeckOps.kt,
 * FlashcardsNoteOps.kt and FlashcardsSettingsOps.kt (TooManyFunctions/LargeClass
 * split); behavior identical.
 */
class FlashcardsViewModel(
    application: Application,
    internal val repository: FlashcardsRepository,
) : AndroidViewModel(application) {

    internal val ds = DataStoreUtils.getInstance(application)

    val decks: StateFlow<List<Deck>> = repository.decks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All cards across every deck; the deck list derives per-deck counts from this. */
    val cards: StateFlow<List<Card>> = repository.cards
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All notes across every deck; the note-type manager derives per-type counts from this. */
    val notes: StateFlow<List<Note>> = repository.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every note type with its ordered fields and templates. */
    val noteTypes: StateFlow<List<NoteTypeWithConfig>> = combine(
        repository.noteTypes,
        repository.noteTypeFields,
        repository.cardTemplates,
    ) { types, fields, templates ->
        types.map { type ->
            NoteTypeWithConfig(
                noteType = type,
                fields = fields.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
                templates = templates.filter { it.noteTypeId == type.id }.sortedBy { it.ord },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        launchIoImpl { FlashcardsViewModelHelper.ensureBuiltInNoteTypes(repository) }
    }

    // -- Persisted settings ------------------------------------------------

    val settings: StateFlow<SettingsUiState> = combine(
        ds.doubleFlow(KEY_RETENTION),
        ds.longFlow(KEY_NEW_PER_DAY, 20L),
        ds.longFlow(KEY_MAX_REVIEWS, 200L),
        ds.longFlow(KEY_THEME_MODE, 0L),
    ) { retention, newPerDay, maxReviews, theme ->
        arrayOf<Any>(retention, newPerDay, maxReviews, theme)
    }
        .combine(ds.booleanFlow(KEY_REMINDER_ENABLED)) { core, enabled -> core to enabled }
        .combine(ds.longFlow(KEY_REMINDER_MINUTES, 20L * 60)) { (core, enabled), minutes ->
            Triple(core, enabled, minutes)
        }
        .combine(ds.booleanFlow(KEY_AUTO_PLAY)) { (core, enabled, minutes), autoPlay ->
            SettingsUiState(
                desiredRetention = (core[0] as Double).takeIf { it > 0.0 } ?: 0.9,
                newPerDay = (core[1] as Long).toInt(),
                maxReviews = (core[2] as Long).toInt(),
                themeMode = (core[3] as Long).toInt(),
                reminderEnabled = enabled,
                reminderHour = (minutes / 60).toInt(),
                reminderMinute = (minutes % 60).toInt(),
                autoPlay = autoPlay,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    // -- Deck presets ------------------------------------------------------

    val deckPresets: StateFlow<List<DeckPreset>> = ds.stringFlow(KEY_DECK_PRESETS)
        .map { parsePresetsImpl(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -- FSRS optimization -------------------------------------------------

    // -- Deck writes -------------------------------------------------------

    // -- Note writes -------------------------------------------------------

    // -- Bulk note operations ---------------------------------------------

    // -- Note type CRUD ----------------------------------------------------

    // -- Review session ----------------------------------------------------

    internal var session: ReviewSession? = null

    /**
     * ReviewLog id written by each undoable session action, in action order
     * (null for actions that write no log, e.g. suspend or a cram grade).
     * [undoReviewImpl] pops one entry per undo so each undo deletes exactly
     * the log its own action wrote — a single `lastLogId` would lose every
     * earlier log after the second grade and delete the wrong log after a
     * suspend.
     */
    internal val reviewLogUndoStack = ArrayDeque<Long?>()
    internal var sessionNotes: Map<Long, Note> = emptyMap()
    internal var sessionDeck: Deck? = null
    internal val reviewState = MutableStateFlow(ReviewUiState())
    val review: StateFlow<ReviewUiState> = reviewState.asStateFlow()

    // -- Text-to-speech ----------------------------------------------------

    internal var tts: TtsSpeaker? = null

    override fun onCleared() {
        tts?.shutdown()
        super.onCleared()
    }

    // -- Import / export / share ------------------------------------------

    internal val shareRequestsFlow = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val shareRequests = shareRequestsFlow.asSharedFlow()

    internal val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = messageFlow.asSharedFlow()

    /** Exports [deckId] (or the whole collection when null) as a shareable `.apkg`. */
    // -- Built-in note types ----------------------------------------------

    companion object {
        const val KEY_RETENTION = "desired_retention"
        const val KEY_NEW_PER_DAY = "new_per_day"
        const val KEY_MAX_REVIEWS = "max_reviews"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_REMINDER_ENABLED = "reminder_enabled"
        const val KEY_REMINDER_MINUTES = "reminder_minutes"
        const val KEY_AUTO_PLAY = "auto_play_audio"
        const val KEY_DECK_PRESETS = "deck_presets"
        const val BASIC_NOTE_TYPE_ID = 1L
        val BUILT_IN_NOTE_TYPE_IDS = setOf(1L, 2L, 3L)
        /** Tag added to a note when one of its cards is auto-suspended as a leech. */
        const val LEECH_TAG = "leech"
        /** Minutes in an hour, for packing reminder hour/minute into minutes. */
        internal const val MINUTES_PER_HOUR = 60
        /** Default new cards per day for a fresh preset. */
        internal const val DEFAULT_NEW_PER_DAY = 20
        /** Default review cap per day for a fresh preset. */
        internal const val DEFAULT_MAX_REVIEWS = 200
        /** Default desired retention for a fresh preset. */
        internal const val DEFAULT_RETENTION = 0.9
        /** Seconds divisor for epoch-millis timestamps. */
        private const val MILLIS_PER_SECOND = 1000L
        /** Bytes per guid group (8 groups of 2 hex chars = 16 chars). */
        private const val GUID_GROUPS = 8
        /** Byte range for random guid bytes. */
        private const val GUID_BYTE_RANGE = 256
        internal fun nowSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND
        /** A 16-hex-char random note guid, matching the migration's format. */
        fun randomGuid(): String = (0 until GUID_GROUPS).joinToString("") {
            "%02x".format(Random.nextInt(0, GUID_BYTE_RANGE))
        }

        /** Mastery = fraction of a deck's cards that have graduated to review. */
        fun mastery(cards: List<Card>): Float {
            if (cards.isEmpty()) return 0f
            return cards.count { it.state == CardState.REVIEW }.toFloat() / cards.size
        }
    }
}

/** Factory for constructing [FlashcardsViewModel] with the repository. */
class FlashcardsViewModelFactory(
    private val application: Application,
    private val repository: FlashcardsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FlashcardsViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return FlashcardsViewModel(application, repository) as T
    }
}
