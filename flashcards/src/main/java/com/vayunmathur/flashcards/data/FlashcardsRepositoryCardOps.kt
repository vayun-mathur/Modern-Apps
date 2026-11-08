package com.vayunmathur.flashcards.data

import com.vayunmathur.flashcards.util.CardGenerator

// ------------------------------------------------------------------
// Card + ReviewLog — moved from FlashcardsRepository.kt
// (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal suspend fun FlashcardsRepository.getAllCardsImpl(): List<Card> = cardDao.getAll()
internal suspend fun FlashcardsRepository.getCardsByDeckImpl(deckId: Long): List<Card> =
    cardDao.getByDeck(deckId)
internal suspend fun FlashcardsRepository.getCardsByIdsImpl(ids: List<Long>): List<Card> =
    cardDao.getByIds(ids)
internal suspend fun FlashcardsRepository.getCardsByNoteImpl(noteId: Long): List<Card> =
    cardDao.getByNote(noteId)
internal suspend fun FlashcardsRepository.getCardsByNotesImpl(noteIds: List<Long>): List<Card> =
    cardDao.getByNotes(noteIds)
internal suspend fun FlashcardsRepository.upsertCardImpl(value: Card): Long = cardDao.upsert(value)
internal suspend fun FlashcardsRepository.upsertCardsImpl(values: List<Card>) = cardDao.upsertAll(values)
internal suspend fun FlashcardsRepository.deleteCardImpl(value: Card): Int = cardDao.delete(value)
internal suspend fun FlashcardsRepository.setCardsSuspendedImpl(ids: List<Long>, value: Int) =
    cardDao.setSuspended(ids, value)
internal suspend fun FlashcardsRepository.setCardsSuspendedByNotesImpl(noteIds: List<Long>, value: Int) =
    cardDao.setSuspendedByNotes(noteIds, value)
internal suspend fun FlashcardsRepository.moveCardsByNotesImpl(noteIds: List<Long>, deckId: Long) =
    cardDao.moveByNotes(noteIds, deckId)
internal suspend fun FlashcardsRepository.deleteCardsByDeckImpl(deckId: Long) = cardDao.deleteByDeck(deckId)
internal suspend fun FlashcardsRepository.deleteCardsByNoteImpl(noteId: Long) = cardDao.deleteByNote(noteId)
internal suspend fun FlashcardsRepository.deleteCardsByNotesImpl(noteIds: List<Long>) =
    cardDao.deleteByNotes(noteIds)
internal suspend fun FlashcardsRepository.deleteRemovedTemplatesImpl(noteId: Long, keepOrds: List<Int>) =
    cardDao.deleteRemovedTemplates(noteId, keepOrds)

/** Wrapper over [CardGenerator.regenerate] using this repository's [CardDao]. */
internal suspend fun FlashcardsRepository.regenerateCardsImpl(
    note: Note,
    noteType: NoteType,
    templates: List<CardTemplate>,
    fields: List<NoteTypeField>,
) {
    val beforeIds = cardDao.getByNote(note.id).map { it.id }
    CardGenerator.regenerate(note, noteType, templates, fields, cardDao)
    // Cards removed by regeneration must take their review history with
    // them (the note-deletion paths already delete logs with cards);
    // orphaned logs would keep counting in deck stats and be replayed
    // as phantom cards by the FSRS optimizer.
    val afterIds = cardDao.getByNote(note.id).map { it.id }.toSet()
    val removedIds = beforeIds.filter { it !in afterIds }
    if (removedIds.isNotEmpty()) reviewLogDao.deleteByCards(removedIds)
}

internal suspend fun FlashcardsRepository.getReviewLogsByDeckOrderedImpl(deckId: Long): List<ReviewLog> =
    reviewLogDao.getByDeckOrdered(deckId)
internal suspend fun FlashcardsRepository.getReviewLogsByCardImpl(cardId: Long): List<ReviewLog> =
    reviewLogDao.getByCard(cardId)
internal suspend fun FlashcardsRepository.insertReviewLogImpl(value: ReviewLog): Long =
    reviewLogDao.insert(value)
internal suspend fun FlashcardsRepository.deleteReviewLogByIdImpl(id: Long) = reviewLogDao.deleteById(id)
internal suspend fun FlashcardsRepository.deleteReviewLogsByCardsImpl(cardIds: List<Long>) =
    reviewLogDao.deleteByCards(cardIds)
internal suspend fun FlashcardsRepository.moveReviewLogsByNotesImpl(noteIds: List<Long>, deckId: Long) =
    reviewLogDao.moveByNotes(noteIds, deckId)
internal suspend fun FlashcardsRepository.deleteReviewLogsByDeckImpl(deckId: Long) =
    reviewLogDao.deleteByDeck(deckId)
