package com.vayunmathur.flashcards.util

import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeField
import com.vayunmathur.flashcards.data.NoteTypeKind
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApkgRoundTripTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db: FlashcardsDatabase = Room.inMemoryDatabaseBuilder(ctx, FlashcardsDatabase::class.java)
        .allowMainThreadQueries().build()
    private val db2: FlashcardsDatabase = Room.inMemoryDatabaseBuilder(ctx, FlashcardsDatabase::class.java)
        .allowMainThreadQueries().build()

    @AfterTest
    fun tearDown() { db.close(); db2.close() }

    private fun sampleConfig(): NoteTypeWithConfig {
        val type = NoteType(id = 10, name = "Basic", type = NoteTypeKind.STANDARD, css = ".card{}", mod = 5)
        val fields = listOf(
            NoteTypeField(id = 1, noteTypeId = 10, ord = 0, name = "Front"),
            NoteTypeField(id = 2, noteTypeId = 10, ord = 1, name = "Back"),
        )
        val templates = listOf(
            CardTemplate(id = 1, noteTypeId = 10, ord = 0, name = "Card 1", qfmt = "{{Front}}", afmt = "{{FrontSide}}\n\n---\n\n{{Back}}"),
        )
        return NoteTypeWithConfig(type, fields, templates)
    }

    @Test
    fun exportThenImportRoundTripsDecksNotesAndCards() = runTest {
        val cfg = sampleConfig()
        val deck = Deck(id = 7, name = "Geography")
        val note = Note(
            id = 42, noteTypeId = 10, deckId = 7, guid = "guid-42",
            flds = "Capital of France?Paris", sortField = "Capital of France?", tags = "geo europe", mod = 9,
        )
        val card = Card(id = 100, noteId = 42, templateOrd = 0, deckId = 7, position = 1.0)
        val file = ApkgExport.write(ctx, "Geo Deck!", listOf(deck), listOf(note), listOf(card), listOf(cfg))
        assertTrue(file.exists() && file.length() > 0)
        assertTrue(file.name.endsWith(".apkg"))

        val message = ApkgImport.import(
            ctx, Uri.fromFile(file),
            db2.deckDao(), db2.noteTypeDao(), db2.noteTypeFieldDao(), db2.cardTemplateDao(), db2.noteDao(), db2.cardDao(),
        )
        assertEquals("Imported 1 notes and 1 cards", message)
        val decks = db2.deckDao().getAll()
        assertEquals(listOf("Geography"), decks.map { it.name })
        val notes = db2.noteDao().getAll()
        assertEquals(1, notes.size)
        assertEquals("guid-42", notes.single().guid)
        assertEquals("Capital of France?", notes.single().fieldList[0])
        assertEquals("Paris", notes.single().fieldList[1])
        assertEquals("geo europe", notes.single().tags)
        assertEquals(decks.single().id, notes.single().deckId)
        val cards = db2.cardDao().getAll()
        assertEquals(1, cards.size)
        assertEquals(notes.single().id, cards.single().noteId)
        assertEquals(decks.single().id, cards.single().deckId)
        val types = db2.noteTypeDao().getAll()
        assertEquals(listOf("Basic"), types.map { it.name })
        assertEquals(listOf("Front", "Back"), db2.noteTypeFieldDao().getByNoteType(types.single().id).map { it.name })
        assertEquals(1, db2.cardTemplateDao().getByNoteType(types.single().id).size)
    }

    @Test
    fun exportEmptyCollectionStillImports() = runTest {
        val file = ApkgExport.write(ctx, "", emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(file.exists())
        val message = ApkgImport.import(
            ctx, Uri.fromFile(file),
            db2.deckDao(), db2.noteTypeDao(), db2.noteTypeFieldDao(), db2.cardTemplateDao(), db2.noteDao(), db2.cardDao(),
        )
        assertEquals("Imported 0 notes and 0 cards", message)
        // Fallback deck is created when an apkg has no used decks? Empty import creates one fallback.
        assertTrue(db2.deckDao().getAll().isNotEmpty())
    }

    @Test
    fun importRejectsZipWithoutCollection() = runTest {
        val bad = File(ctx.cacheDir, "bad.apkg")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt")); zip.write("hi".toByteArray()); zip.closeEntry()
        }
        val ex = assertFailsWith<ApkgImport.ApkgFormatException> {
            ApkgImport.import(ctx, Uri.fromFile(bad), db.deckDao(), db.noteTypeDao(), db.noteTypeFieldDao(), db.cardTemplateDao(), db.noteDao(), db.cardDao())
        }
        assertEquals("Not a valid .apkg file", ex.message)
    }

    @Test
    fun importRejectsNewerOnlyFormat() = runTest {
        val bad = File(ctx.cacheDir, "newer.apkg")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki21b")); zip.write("x".toByteArray()); zip.closeEntry()
        }
        val ex = assertFailsWith<ApkgImport.ApkgFormatException> {
            ApkgImport.import(ctx, Uri.fromFile(bad), db.deckDao(), db.noteTypeDao(), db.noteTypeFieldDao(), db.cardTemplateDao(), db.noteDao(), db.cardDao())
        }
        assertTrue(ex.message!!.contains("newer Anki format"))
    }

    @Test
    fun importRejectsUnsafeZipEntry() = runTest {
        val bad = File(ctx.cacheDir, "unsafe.apkg")
        ZipOutputStream(bad.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../evil.txt")); zip.write("x".toByteArray()); zip.closeEntry()
        }
        val ex = assertFailsWith<ApkgImport.ApkgFormatException> {
            ApkgImport.import(ctx, Uri.fromFile(bad), db.deckDao(), db.noteTypeDao(), db.noteTypeFieldDao(), db.cardTemplateDao(), db.noteDao(), db.cardDao())
        }
        assertTrue(ex.message!!.contains("Unsafe zip entry"))
    }
}
