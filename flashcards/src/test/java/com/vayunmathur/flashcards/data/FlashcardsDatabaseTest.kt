package com.vayunmathur.flashcards.data

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardsDatabaseTest {

    private lateinit var db: FlashcardsDatabase

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, FlashcardsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() = db.close()

    private suspend fun deck(name: String = "Spanish"): Long = db.deckDao().upsert(Deck(name = name))

    @Test
    fun deckCrudAndFlow() = runTest {
        assertTrue(db.deckDao().getAll().isEmpty())
        val id = deck()
        assertEquals("Spanish", db.deckDao().getById(id)?.name)
        assertEquals(listOf("Spanish"), db.deckDao().getAllFlow().first().map { it.name })
        db.deckDao().upsert(Deck(id = id, name = "French", newPerDay = 5))
        assertEquals(5, db.deckDao().getById(id)?.newPerDay)
        assertEquals(1, db.deckDao().delete(db.deckDao().getById(id)!!))
        assertNull(db.deckDao().getById(id))
    }

    @Test
    fun noteTypeFieldsAndTemplatesAreOrdered() = runTest {
        val typeId = db.noteTypeDao().upsert(NoteType(name = "Basic", type = NoteTypeKind.STANDARD, css = "x"))
        db.noteTypeFieldDao().upsertAll(
            listOf(
                NoteTypeField(noteTypeId = typeId, ord = 1, name = "Back"),
                NoteTypeField(noteTypeId = typeId, ord = 0, name = "Front"),
            ),
        )
        db.cardTemplateDao().upsertAll(
            listOf(
                CardTemplate(noteTypeId = typeId, ord = 1, name = "Rev", qfmt = "{{Back}}", afmt = "{{Front}}"),
                CardTemplate(noteTypeId = typeId, ord = 0, name = "Fwd", qfmt = "{{Front}}", afmt = "{{Back}}"),
            ),
        )
        assertEquals(listOf("Front", "Back"), db.noteTypeFieldDao().getByNoteType(typeId).map { it.name })
        assertEquals(listOf("Fwd", "Rev"), db.cardTemplateDao().getByNoteType(typeId).map { it.name })
        assertEquals(typeId, db.noteTypeDao().getById(typeId)?.id)
        db.noteTypeFieldDao().deleteByNoteType(typeId)
        db.cardTemplateDao().deleteByNoteType(typeId)
        assertTrue(db.noteTypeFieldDao().getByNoteType(typeId).isEmpty())
        assertTrue(db.cardTemplateDao().getByNoteType(typeId).isEmpty())
        assertEquals(1, db.noteTypeDao().delete(db.noteTypeDao().getById(typeId)!!))
    }

    @Test
    fun noteQueriesByDeckTypeAndIds() = runTest {
        val d1 = deck("D1"); val d2 = deck("D2")
        val n1 = db.noteDao().upsert(Note(noteTypeId = 1, deckId = d1, guid = "g1", flds = "ab", sortField = "a", tags = "t1"))
        val n2 = db.noteDao().upsert(Note(noteTypeId = 2, deckId = d1, guid = "g2", flds = "cd", sortField = "c"))
        db.noteDao().upsert(Note(noteTypeId = 1, deckId = d2, guid = "g3", flds = "ef", sortField = "e"))
        assertEquals(2, db.noteDao().getByDeck(d1).size)
        assertEquals(listOf(n1, n2), db.noteDao().getByIds(listOf(n1, n2)).map { it.id }.sorted())
        assertEquals(2, db.noteDao().getByNoteType(1).size)
        assertEquals("g2", db.noteDao().getByIdFlow(n2).first()?.guid)
        assertEquals(2, db.noteDao().getByDeckFlow(d1).first().size)
        db.noteDao().deleteByIds(listOf(n1))
        assertNull(db.noteDao().getById(n1))
        db.noteDao().deleteByDeck(d1)
        assertTrue(db.noteDao().getByDeck(d1).isEmpty())
        assertEquals(1, db.noteDao().getAll().size)
    }

    @Test
    fun cardQueriesSuspendMoveAndDelete() = runTest {
        val d1 = deck("D1"); val d2 = deck("D2")
        val c1 = db.cardDao().upsert(Card(noteId = 10, templateOrd = 0, deckId = d1, dueDate = 100))
        val c2 = db.cardDao().upsert(Card(noteId = 10, templateOrd = 1, deckId = d1, dueDate = 50))
        val c3 = db.cardDao().upsert(Card(noteId = 11, templateOrd = 0, deckId = d1, dueDate = 10_000))
        assertEquals(2, db.cardDao().getByNote(10).size)
        assertEquals(setOf(c1, c2, c3), db.cardDao().getByNotes(listOf(10, 11)).map { it.id }.toSet())
        assertEquals(listOf(c1, c2), db.cardDao().getDueByDeckFlow(d1, 100).first().map { it.id }.sorted())
        db.cardDao().setSuspended(listOf(c1), 1)
        assertEquals(1, db.cardDao().getByIds(listOf(c1)).single().suspended)
        // Suspended cards are excluded from due flow.
        assertEquals(listOf(c2), db.cardDao().getDueByDeckFlow(d1, 100).first().map { it.id })
        db.cardDao().setSuspendedByNotes(listOf(11), 1)
        assertEquals(1, db.cardDao().getByNote(11).single().suspended)
        db.cardDao().moveByNotes(listOf(10), d2)
        assertTrue(db.cardDao().getByNote(10).all { it.deckId == d2 })
        db.cardDao().deleteRemovedTemplates(10, listOf(0))
        assertEquals(listOf(0), db.cardDao().getByNote(10).map { it.templateOrd })
        db.cardDao().deleteByNotes(listOf(11))
        assertTrue(db.cardDao().getByNote(11).isEmpty())
        db.cardDao().deleteByDeck(d2)
        assertTrue(db.cardDao().getByDeck(d2).isEmpty())
        assertTrue(db.cardDao().getAll().isEmpty())
        val extra = db.cardDao().upsert(Card(noteId = 99, templateOrd = 0, deckId = d1))
        assertEquals(1, db.cardDao().delete(db.cardDao().getByIds(listOf(extra)).single()))
    }

    @Test
    fun reviewLogQueriesAndDeletes() = runTest {
        val log1 = db.reviewLogDao().insert(ReviewLog(cardId = 1, deckId = 7, reviewedAt = 200, grade = 3, elapsedDays = 1.0, scheduledDays = 2.0, state = 2))
        db.reviewLogDao().insert(ReviewLog(cardId = 1, deckId = 7, reviewedAt = 100, grade = 1, elapsedDays = 0.0, scheduledDays = 0.1, state = 1))
        db.reviewLogDao().insert(ReviewLog(cardId = 2, deckId = 8, reviewedAt = 300, grade = 4, elapsedDays = 2.0, scheduledDays = 4.0, state = 2))
        assertEquals(listOf(100L, 200L), db.reviewLogDao().getByDeckOrdered(7).map { it.reviewedAt })
        assertEquals(2, db.reviewLogDao().getByCard(1).size)
        assertEquals(3, db.reviewLogDao().getAllFlow().first().size)
        assertEquals(2, db.reviewLogDao().getByDeckFlow(7).first().size)
        db.reviewLogDao().deleteById(log1)
        assertEquals(1, db.reviewLogDao().getByCard(1).size)
        db.reviewLogDao().deleteByCards(listOf(1))
        assertTrue(db.reviewLogDao().getByCard(1).isEmpty())
        db.reviewLogDao().deleteByDeck(8)
        assertTrue(db.reviewLogDao().getAllFlow().first().isEmpty())
    }

    @Test
    fun entityHelpers() {
        val note = Note(noteTypeId = 1, deckId = 1, guid = "g", flds = "frontbackextra", sortField = "front")
        assertEquals(listOf("front", "back", "extra"), note.fieldList)
        val fields = listOf(
            NoteTypeField(noteTypeId = 1, ord = 1, name = "Back"),
            NoteTypeField(noteTypeId = 1, ord = 0, name = "Front"),
            NoteTypeField(noteTypeId = 1, ord = 2, name = "Missing"),
        )
        // fieldValues is keyed in ord order; extra note values beyond fields dropped by map size.
        assertEquals(mapOf("Front" to "front", "Back" to "back", "Missing" to "extra"), note.fieldValues(fields))
        assertEquals("front", note.copy().sortField)
        assertTrue(Card(noteId = 1, templateOrd = 0, deckId = 1).isNew)
        assertTrue(Card(noteId = 1, templateOrd = 0, deckId = 1, suspended = 1).isSuspended)
        assertEquals(3.0, Deck(name = "x").withPosition(3.0).position)
        assertEquals(2.0, note.withPosition(2.0).position)
        assertEquals("flashcards-db", DB_NAME)
    }
}
