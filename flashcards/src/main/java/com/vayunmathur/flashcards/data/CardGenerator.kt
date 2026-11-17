package com.vayunmathur.flashcards.data
import com.vayunmathur.flashcards.domain.TemplateEngine

import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField
import com.vayunmathur.flashcards.data.NoteTypeKind

/**
 * Decides which [Card]s a [Note] should generate and keeps the stored cards in
 * sync with its note type's templates. The [findClozeOrds]/[requiredOrds]
 * decisions are pure Kotlin (unit-testable); [regenerate] applies them to the DB.
 */
object CardGenerator {

    private val clozeNumberRegex = Regex("""\{\{c(\d+)::""")

    /** The set of card ords a note requires: cloze indices, or non-empty templates. */
    fun requiredOrds(
        noteType: NoteType,
        templates: List<CardTemplate>,
        fields: List<NoteTypeField>,
        flds: String,
    ): Set<Int> {
        if (noteType.type == NoteTypeKind.CLOZE) return findClozeOrds(flds)

        val note = Note(noteTypeId = noteType.id, deckId = 0, guid = "", flds = flds, sortField = "")
        val values = note.fieldValues(fields)
        return templates
            .filter { template ->
                val (front, _) = TemplateEngine.render(template.qfmt, template.afmt, values, clozeOrd = null)
                front.isNotBlank()
            }
            .map { it.ord }
            .toSet()
    }

    /** Cloze card ords (cloze number − 1) present anywhere in [flds]. */
    fun findClozeOrds(flds: String): Set<Int> =
        clozeNumberRegex.findAll(flds)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 1 }
            .map { it - 1 }
            .toSet()

    /**
     * Regenerates [note]'s cards after a save or a note-type change: inserts cards
     * for newly-required ords (assigning the next deck position), leaves existing
     * cards' scheduling untouched, and deletes cards for ords that are no longer
     * required.
     */
    suspend fun regenerate(
        note: Note,
        noteType: NoteType,
        templates: List<CardTemplate>,
        fields: List<NoteTypeField>,
        cardDao: CardDao,
    ) {
        val required = requiredOrds(noteType, templates, fields, note.flds)
        if (required.isEmpty()) {
            cardDao.deleteByNote(note.id)
            return
        }
        cardDao.deleteRemovedTemplates(note.id, required.toList())

        val existingOrds = cardDao.getByNote(note.id).map { it.templateOrd }.toSet()
        val newOrds = required.filter { it !in existingOrds }.sorted()
        if (newOrds.isNotEmpty()) {
            var position = cardDao.getByDeck(note.deckId).maxOfOrNull { it.position } ?: 0.0
            val newCards = newOrds.map { ord ->
                position += 1.0
                Card(noteId = note.id, templateOrd = ord, deckId = note.deckId, position = position)
            }
            cardDao.upsertAll(newCards)
        }
    }
}
