package com.vayunmathur.flashcards.util

import android.app.Application
import android.net.Uri
import androidx.core.content.FileProvider
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardState
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.flashcards.data.ReviewLog
import com.vayunmathur.flashcards.data.cardsForImpl
import com.vayunmathur.flashcards.data.deleteReviewLogByIdImpl
import com.vayunmathur.flashcards.data.getAllCardsImpl
import com.vayunmathur.flashcards.data.getAllDecksImpl
import com.vayunmathur.flashcards.data.getAllNotesImpl
import com.vayunmathur.flashcards.data.getCardsByDeckImpl
import com.vayunmathur.flashcards.data.getDeckImpl
import com.vayunmathur.flashcards.data.getNoteImpl
import com.vayunmathur.flashcards.data.getNotesByDeckImpl
import com.vayunmathur.flashcards.data.getReviewLogsByDeckOrderedImpl
import com.vayunmathur.flashcards.data.insertReviewLogImpl
import com.vayunmathur.flashcards.data.noteByIdImpl
import com.vayunmathur.flashcards.data.notesForImpl
import com.vayunmathur.flashcards.data.regenerateCardsImpl
import com.vayunmathur.flashcards.data.reviewLogsForImpl
import com.vayunmathur.flashcards.data.setCardsSuspendedImpl
import com.vayunmathur.flashcards.data.upsertCardImpl
import com.vayunmathur.flashcards.data.upsertDeckImpl
import com.vayunmathur.flashcards.data.upsertNoteImpl
import com.vayunmathur.flashcards.R
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------
// Settings + presets + FSRS optimization + reminder + review session +
// TTS + import/export/share + JSON helpers — moved from
// FlashcardsViewModel.kt (TooManyFunctions/LargeClass split); behavior identical.
// ------------------------------------------------------------------

internal fun FlashcardsViewModel.notesForImpl(deckId: Long): Flow<List<Note>> = repository.notesForImpl(deckId)

internal fun FlashcardsViewModel.noteByIdImpl(id: Long): Flow<Note?> = repository.noteByIdImpl(id)

internal fun FlashcardsViewModel.cardsForImpl(deckId: Long): Flow<List<Card>> = repository.cardsForImpl(deckId)

internal fun FlashcardsViewModel.reviewLogsForImpl(deckId: Long?): Flow<List<ReviewLog>> =
    repository.reviewLogsForImpl(deckId)

internal fun FlashcardsViewModel.setDesiredRetentionImpl(value: Double) =
    launchIoImpl { ds.setDouble(FlashcardsViewModel.KEY_RETENTION, value) }

internal fun FlashcardsViewModel.setNewPerDayImpl(value: Int) =
    launchIoImpl { ds.setLong(FlashcardsViewModel.KEY_NEW_PER_DAY, value.toLong()) }

internal fun FlashcardsViewModel.setMaxReviewsImpl(value: Int) =
    launchIoImpl { ds.setLong(FlashcardsViewModel.KEY_MAX_REVIEWS, value.toLong()) }

internal fun FlashcardsViewModel.setThemeModeImpl(mode: Int) =
    launchIoImpl { ds.setLong(FlashcardsViewModel.KEY_THEME_MODE, mode.toLong()) }

internal fun FlashcardsViewModel.setAutoPlayImpl(enabled: Boolean) =
    launchIoImpl { ds.setBoolean(FlashcardsViewModel.KEY_AUTO_PLAY, enabled) }

/** Saves the current study settings under [name] as a reusable preset. */
internal fun FlashcardsViewModel.saveDeckPresetImpl(name: String) = launchIoImpl {
    val clean = name.trim()
    if (clean.isEmpty()) return@launchIoImpl
    val s = settings.value
    val map = parsePresetsImpl(ds.getString(FlashcardsViewModel.KEY_DECK_PRESETS).orEmpty())
        .associateBy { it.name }
        .toMutableMap()
    map[clean] = DeckPreset(clean, s.newPerDay, s.maxReviews, s.desiredRetention)
    ds.setString(FlashcardsViewModel.KEY_DECK_PRESETS, presetsToJsonImpl(map.values.toList()))
    AppMessages.show(getApplication<Application>().getString(R.string.preset_saved))
}

/** Applies preset [name] to the global defaults and every existing deck. */
internal fun FlashcardsViewModel.applyDeckPresetImpl(name: String) = launchIoImpl {
    val preset = parsePresetsImpl(ds.getString(FlashcardsViewModel.KEY_DECK_PRESETS).orEmpty())
        .firstOrNull { it.name == name } ?: return@launchIoImpl
    ds.setLong(FlashcardsViewModel.KEY_NEW_PER_DAY, preset.newPerDay.toLong())
    ds.setLong(FlashcardsViewModel.KEY_MAX_REVIEWS, preset.maxReviews.toLong())
    ds.setDouble(FlashcardsViewModel.KEY_RETENTION, preset.desiredRetention)
    repository.getAllDecksImpl().forEach { deck ->
        repository.upsertDeckImpl(
            deck.copy(
                newPerDay = preset.newPerDay,
                maxReviewsPerDay = preset.maxReviews,
                desiredRetention = preset.desiredRetention,
            ),
        )
    }
    AppMessages.show(getApplication<Application>().getString(R.string.preset_applied))
}

internal fun FlashcardsViewModel.deleteDeckPresetImpl(name: String) = launchIoImpl {
    val presetsRaw = ds.getString(FlashcardsViewModel.KEY_DECK_PRESETS).orEmpty()
    val remaining = parsePresetsImpl(presetsRaw).filterNot { it.name == name }
    ds.setString(FlashcardsViewModel.KEY_DECK_PRESETS, presetsToJsonImpl(remaining))
}

/**
 * Runs the pragmatic [FsrsOptimizer] on every deck with enough review history,
 * saving the tuned weights per deck. Reports the outcome via [AppMessages].
 */
internal fun FlashcardsViewModel.optimizeAllDecksImpl() = launchIoImpl {
    val ctx = getApplication<Application>()
    var optimizedReviews = 0
    var optimizedDecks = 0
    var mostReviews = 0
    repository.getAllDecksImpl().forEach { deck ->
        val logs = repository.getReviewLogsByDeckOrderedImpl(deck.id)
        mostReviews = maxOf(mostReviews, logs.size)
        val byCard = logs.groupBy { it.cardId }.values.toList()
        if (FsrsOptimizer.hasEnough(byCard)) {
            val weights = withContext(Dispatchers.Default) { FsrsOptimizer.optimize(byCard) }
            repository.upsertDeckImpl(deck.copy(fsrsWeights = Scheduler.weightsToJson(weights)))
            optimizedReviews += logs.size
            optimizedDecks++
        }
    }
    if (optimizedDecks > 0) {
        AppMessages.show(ctx.getString(R.string.optimize_fsrs_done, optimizedReviews))
    } else {
        AppMessages.show(ctx.getString(R.string.optimize_fsrs_too_few, mostReviews, FsrsOptimizer.MIN_REVIEWS))
    }
}

internal fun FlashcardsViewModel.setReminderEnabledImpl(enabled: Boolean) = launchIoImpl {
    ds.setBoolean(FlashcardsViewModel.KEY_REMINDER_ENABLED, enabled)
    val current = settings.value
    ReviewReminder.update(getApplication(), enabled, current.reminderHour, current.reminderMinute)
}

internal fun FlashcardsViewModel.setReminderTimeImpl(hour: Int, minute: Int) = launchIoImpl {
    val reminderMinutes = (hour * FlashcardsViewModel.MINUTES_PER_HOUR + minute).toLong()
    ds.setLong(FlashcardsViewModel.KEY_REMINDER_MINUTES, reminderMinutes)
    ReviewReminder.update(getApplication(), settings.value.reminderEnabled, hour, minute)
}

internal fun FlashcardsViewModel.startSessionImpl(
    deckId: Long,
    params: StudyParams = StudyParams(),
    tagFilter: Set<String> = emptySet(),
) = launchIoImpl {
    val deck = repository.getDeckImpl(deckId) ?: return@launchIoImpl
    val notes = repository.getNotesByDeckImpl(deckId)
    sessionNotes = notes.associateBy { it.id }
    sessionDeck = deck
    val allCards = repository.getCardsByDeckImpl(deckId)
    val cards = if (tagFilter.isEmpty()) {
        allCards
    } else {
        val allowedNoteIds = notes
            .filter { note -> note.tags.split(" ").any { it in tagFilter } }
            .map { it.id }
            .toSet()
        allCards.filter { it.noteId in allowedNoteIds }
    }
    val now = System.currentTimeMillis()
    session = ReviewSession(
        cards = cards,
        newPerDay = deck.newPerDay,
        maxReviews = deck.maxReviewsPerDay,
        now = now,
        desiredRetention = deck.desiredRetention,
        weights = Scheduler.parseWeights(deck.fsrsWeights),
        params = params,
    )
    reviewLogUndoStack.clear()
    publishReviewImpl()
}

internal fun FlashcardsViewModel.gradeCurrentImpl(grade: Grade) = launchIoImpl {
    val s = session ?: return@launchIoImpl
    val original = s.current ?: return@launchIoImpl
    val now = System.currentTimeMillis()
    val prevLastReview = original.lastReview
    val wasReview = original.state == CardState.REVIEW
    val updated = s.grade(grade, now) ?: return@launchIoImpl
    // Cram mode is preview-only: don't persist scheduling or a review log.
    if (s.previewOnly) {
        reviewLogUndoStack.addLast(null)
        publishReviewImpl()
        return@launchIoImpl
    }
    repository.upsertCardImpl(updated)
    maybeMarkLeechImpl(updated, grade, wasReview)
    val elapsedDays =
        if (prevLastReview > 0) (now - prevLastReview).toDouble() / Scheduler.DAY_MS else 0.0
    val scheduledDays = (updated.dueDate - now).toDouble() / Scheduler.DAY_MS
    reviewLogUndoStack.addLast(
        repository.insertReviewLogImpl(
        ReviewLog(
            cardId = original.id,
            deckId = original.deckId,
            reviewedAt = now,
            grade = grade.value,
            elapsedDays = elapsedDays,
            scheduledDays = scheduledDays,
            state = updated.state,
        ),
        ),
    )
    publishReviewImpl()
}

/**
 * Auto-suspends a card the first time it reaches `leechThreshold` lapses (Anki's
 * leech behaviour): tags its note, evicts the just-re-queued copy from the
 * in-session queue, and surfaces a message. Only fires on the crossing lapse.
 */
internal suspend fun FlashcardsViewModel.maybeMarkLeechImpl(updated: Card, grade: Grade, wasReview: Boolean) {
    val threshold = sessionDeck?.leechThreshold ?: 0
    if (threshold <= 0 || grade != Grade.AGAIN || !wasReview) return
    if (updated.lapses != threshold) return
    repository.setCardsSuspendedImpl(listOf(updated.id), 1)
    session?.removeFromQueue(updated.id)
    val note = repository.getNoteImpl(updated.noteId)
    if (note != null && !note.tags.split(" ").contains(FlashcardsViewModel.LEECH_TAG)) {
        repository.upsertNoteImpl(note.copy(tags = (note.tags + " " + FlashcardsViewModel.LEECH_TAG).trim()))
    }
    AppMessages.show(getApplication<Application>().getString(R.string.leech_suspended))
}

internal fun FlashcardsViewModel.undoReviewImpl() = launchIoImpl {
    val s = session ?: return@launchIoImpl
    val restored = s.undo() ?: return@launchIoImpl
    repository.upsertCardImpl(restored)
    reviewLogUndoStack.removeLastOrNull()?.let { repository.deleteReviewLogByIdImpl(it) }
    publishReviewImpl()
}

/** Speaks [text] aloud (markdown/HTML stripped). No-op on blank. */
internal fun FlashcardsViewModel.speakImpl(text: String) {
    val clean = Regex("<[^>]+>").replace(text, "")
        .replace(Regex("""[*_`#>\[\]]"""), "")
        .trim()
    if (clean.isEmpty()) return
    val speaker = tts ?: TtsSpeaker(getApplication()).also { tts = it }
    speaker.speak(clean)
}

internal data class RenderedCardImpl(
    val front: String,
    val back: String,
    val typeField: String?,
    val typeAnswer: String?,
)

internal fun FlashcardsViewModel.publishReviewImpl() {
    val s = session
    reviewState.value = if (s == null) {
        ReviewUiState(done = true)
    } else {
        val now = System.currentTimeMillis()
        val current = s.current
        val rendered = current?.let { renderCardImpl(it) }
            ?: RenderedCardImpl("", "", null, null)
        ReviewUiState(
            front = rendered.front,
            back = rendered.back,
            remaining = s.remaining,
            done = s.done,
            newCount = s.newCount,
            learningCount = s.learningCount,
            reviewCount = s.reviewCount,
            progress = s.progress,
            intervalLabels = s.previewLabels(now),
            canUndo = s.canUndo,
            typeField = rendered.typeField,
            typeAnswer = rendered.typeAnswer,
            autoPlay = settings.value.autoPlay,
        )
    }
}

/** Renders a card's markdown (+ any type-in field) from its note and template. */
internal fun FlashcardsViewModel.renderCardImpl(card: Card): RenderedCardImpl {
    val note = sessionNotes[card.noteId] ?: return RenderedCardImpl("", "", null, null)
    val cfg = noteTypes.value.firstOrNull { it.noteType.id == note.noteTypeId }
        ?: return RenderedCardImpl(note.sortField, "", null, null)
    val values = note.fieldValues(cfg.fields)
    val template = if (cfg.noteType.type == NoteTypeKind.CLOZE) {
        cfg.templates.firstOrNull()
    } else {
        cfg.templates.firstOrNull { it.ord == card.templateOrd } ?: cfg.templates.firstOrNull()
    } ?: return RenderedCardImpl(note.sortField, "", null, null)
    val clozeOrd = if (cfg.noteType.type == NoteTypeKind.CLOZE) card.templateOrd else null
    val (front, back) = TemplateEngine.render(template.qfmt, template.afmt, values, clozeOrd)
    val typeField = TemplateEngine.typeField(template.qfmt)
    val typeAnswer = typeField?.let { values[it] }
    return RenderedCardImpl(front, back, typeField, typeAnswer)
}

/** Exports [deckId] (or the whole collection when null) as a shareable `.apkg`. */
internal fun FlashcardsViewModel.exportApkgImpl(deckId: Long?) = launchIoImpl {
    val context = getApplication<Application>()
    val exportedDecks =
        if (deckId == null) repository.getAllDecksImpl() else listOfNotNull(repository.getDeckImpl(deckId))
    val notes = if (deckId == null) repository.getAllNotesImpl() else repository.getNotesByDeckImpl(deckId)
    val noteIds = notes.map { it.id }.toSet()
    val exportedCards = repository.getAllCardsImpl().filter { it.noteId in noteIds }
    val configs = noteTypes.value
    val uri = withContext(Dispatchers.IO) {
        val name = exportedDecks.singleOrNull()?.name ?: "collection"
        val file = ApkgExport.write(context, name, exportedDecks, notes, exportedCards, configs)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    shareRequestsFlow.emit(uri)
}

internal fun FlashcardsViewModel.importApkgImpl(uri: Uri) = launchIoImpl {
    val context = getApplication<Application>()
    val message = withContext(Dispatchers.IO) {
        runCatching {
            repository.importApkg(context, uri)
        }.getOrElse { it.message ?: "Import failed" }
    }
    messageFlow.emit(message)
}

internal fun FlashcardsViewModel.importCsvImpl(deckId: Long, uri: Uri) = launchIoImpl {
    val context = getApplication<Application>()
    val basicId = FlashcardsViewModel.BASIC_NOTE_TYPE_ID
    val cfg = noteTypes.value.firstOrNull { it.noteType.id == basicId } ?: return@launchIoImpl
    val text = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } ?: return@launchIoImpl
    val rows = DeckIo.parseCsv(text)
    if (rows.isEmpty()) return@launchIoImpl
    var position = repository.getNotesByDeckImpl(deckId).maxOfOrNull { it.position } ?: 0.0
    rows.forEach { (front, back) ->
        position += 1.0
        val note = Note(
            noteTypeId = FlashcardsViewModel.BASIC_NOTE_TYPE_ID,
            deckId = deckId,
            guid = FlashcardsViewModel.randomGuid(),
            flds = listOf(front, back).joinToString(FIELD_SEPARATOR),
            sortField = front,
            mod = FlashcardsViewModel.nowSeconds(),
            position = position,
        )
        val id = repository.upsertNoteImpl(note)
        repository.regenerateCardsImpl(note.copy(id = id), cfg.noteType, cfg.templates, cfg.fields)
    }
}

internal fun FlashcardsViewModel.parsePresetsImpl(json: String): List<DeckPreset> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            DeckPreset(
                name = o.optString("name"),
                newPerDay = o.optInt("newPerDay", FlashcardsViewModel.DEFAULT_NEW_PER_DAY),
                maxReviews = o.optInt("maxReviews", FlashcardsViewModel.DEFAULT_MAX_REVIEWS),
                desiredRetention = o.optDouble("desiredRetention", FlashcardsViewModel.DEFAULT_RETENTION),
            )
        }
    }.getOrDefault(emptyList())
}

internal fun FlashcardsViewModel.presetsToJsonImpl(presets: List<DeckPreset>): String {
    val arr = org.json.JSONArray()
    presets.forEach { p ->
        arr.put(
            org.json.JSONObject().apply {
                put("name", p.name)
                put("newPerDay", p.newPerDay)
                put("maxReviews", p.maxReviews)
                put("desiredRetention", p.desiredRetention)
            },
        )
    }
    return arr.toString()
}

/** DataStore handle shared by the settings extensions in this file. */
internal val FlashcardsViewModel.settingsDs: DataStoreUtils
    get() = DataStoreUtils.getInstance(getApplication())
