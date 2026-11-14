package com.vayunmathur.flashcards.data

import android.content.Context
import android.net.Uri
import com.vayunmathur.flashcards.util.ApkgImport
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of [FlashcardsDatabase]. All DB access goes through here so the
 * process builds the database exactly once (via [RoomRepository]'s lazy [db]).
 *
 * All operations live as `internal` extensions in FlashcardsRepositoryFlowsOps.kt,
 * FlashcardsRepositoryFieldsOps.kt, FlashcardsRepositoryNoteOps.kt and
 * FlashcardsRepositoryCardOps.kt (TooManyFunctions split); behavior identical.
 */
class FlashcardsRepository private constructor(context: Context) :
    RoomRepository<FlashcardsDatabase>(context, FlashcardsDatabase::class, DB_NAME) {

    internal val deckDao get() = db.deckDao()
    internal val cardDao get() = db.cardDao()
    internal val reviewLogDao get() = db.reviewLogDao()
    internal val noteTypeDao get() = db.noteTypeDao()
    internal val noteTypeFieldDao get() = db.noteTypeFieldDao()
    internal val cardTemplateDao get() = db.cardTemplateDao()
    internal val noteDao get() = db.noteDao()

    // ------------------------------------------------------------------
    // Read flows (cold)
    // ------------------------------------------------------------------

    val decks: Flow<List<Deck>> get() = decksFlow()
    val cards: Flow<List<Card>> get() = cardsFlow()
    val notes: Flow<List<Note>> get() = notesFlow()
    val noteTypes: Flow<List<NoteType>> get() = noteTypesFlow()
    val noteTypeFields: Flow<List<NoteTypeField>> get() = noteTypeFieldsFlow()
    val cardTemplates: Flow<List<CardTemplate>> get() = cardTemplatesFlow()
    val reviewLogs: Flow<List<ReviewLog>> get() = reviewLogsFlow()

    // ------------------------------------------------------------------
    // Apkg import (delegates to [ApkgImport] with this repository's DAOs)
    // ------------------------------------------------------------------

    suspend fun importApkg(context: Context, uri: Uri): String =
        ApkgImport.import(context, uri, deckDao, noteTypeDao, noteTypeFieldDao, cardTemplateDao, noteDao, cardDao)

    /** Holds the process-wide [FlashcardsRepository] singleton. */
    companion object {
        @Volatile
        private var instance: FlashcardsRepository? = null

        fun get(context: Context): FlashcardsRepository =
            instance ?: synchronized(this) {
                instance ?: FlashcardsRepository(context).also { instance = it }
            }
    }
}
