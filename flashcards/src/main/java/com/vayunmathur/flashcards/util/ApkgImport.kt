package com.vayunmathur.flashcards.util

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.CardDao
import com.vayunmathur.flashcards.data.CardTemplate
import com.vayunmathur.flashcards.data.CardTemplateDao
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.DeckDao
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteDao
import com.vayunmathur.flashcards.data.NoteType
import com.vayunmathur.flashcards.data.NoteTypeDao
import com.vayunmathur.flashcards.data.NoteTypeField
import com.vayunmathur.flashcards.data.NoteTypeFieldDao
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Imports a legacy **schema-11** `.apkg` (a zip of a plain `collection.anki2`).
 * Media is dropped and all imported cards enter as *new* (Anki's SM-2 scheduling
 * is not transferred). Newer zstd-only exports (`collection.anki21b` without a
 * `collection.anki2`) are detected and rejected with a clear message.
 */
object ApkgImport {
    /** Thrown when an imported .apkg file is not in the supported Anki format. */
    class ApkgFormatException(message: String) : Exception(message)

    /** Cursor columns of `SELECT id, nid, did, ord FROM cards`. */
    private const val COL_ID = 0
    private const val COL_NID = 1
    private const val COL_DID = 2
    private const val COL_ORD = 3

    suspend fun import(
        context: Context,
        uri: Uri,
        deckDao: DeckDao,
        noteTypeDao: NoteTypeDao,
        fieldDao: NoteTypeFieldDao,
        templateDao: CardTemplateDao,
        noteDao: NoteDao,
        cardDao: CardDao,
    ): String {
        val work = File(context.cacheDir, "apkg_import_${System.currentTimeMillis()}").apply {
            deleteRecursively(); mkdirs()
        }
        try {
            val temp = File(work, "in.apkg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw ApkgFormatException("Could not open file")

            unzipGuarded(temp, work)

            val anki2 = File(work, "collection.anki2")
            if (!anki2.exists()) {
                val newer = File(work, "collection.anki21b").exists()
                throw ApkgFormatException(
                    if (newer) {
                        "This .apkg uses the newer Anki format. Re-export it with " +
                            "\"Support older Anki versions\" enabled."
                    } else {
                        "Not a valid .apkg file"
                    },
                )
            }

            val db = SQLiteDatabase.openDatabase(anki2.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val result = importCollection(db, deckDao, noteTypeDao, fieldDao, templateDao, noteDao, cardDao)
                importMedia(context, work)
                return result
            } finally {
                db.close()
            }
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Copies numbered media entries out of the unzipped [work] dir into the app's
     * `filesDir/media`, using the names from the `media` JSON manifest so that the
     * `<img src="name">` → `![](name)` conversion resolves. Existing files with the
     * same name are overwritten (imports normally land in a fresh media dir).
     */
    private fun importMedia(context: Context, work: File) {
        val manifest = File(work, "media")
        if (!manifest.exists()) return
        val map = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return
        val store = MediaStore(context)
        val keys = map.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val name = map.optString(key)
            val src = File(work, key)
            if (name.isNotBlank() && src.exists()) {
                runCatching { store.resolve(name).writeBytes(src.readBytes()) }
            }
        }
    }

    private suspend fun importCollection(
        db: SQLiteDatabase,
        deckDao: DeckDao,
        noteTypeDao: NoteTypeDao,
        fieldDao: NoteTypeFieldDao,
        templateDao: CardTemplateDao,
        noteDao: NoteDao,
        cardDao: CardDao,
    ): String {
        val (modelsJson, decksJson) = readCollectionHeader(db)
        val modelIdMap = importNoteTypes(modelsJson, noteTypeDao, fieldDao, templateDao)
        val rawCards = readRawCards(db)
        val deckIdMap = importDecks(rawCards.map { it.did }.toSet(), decksJson, deckDao)
        val fallbackDeckId = deckIdMap.values.firstOrNull()
            ?: deckDao.upsert(Deck(name = "Imported"))
        val noteIdMap = importNotes(db, rawCards, modelIdMap, deckIdMap, fallbackDeckId, noteDao)
        val newCards = buildCards(rawCards, noteIdMap, deckIdMap, fallbackDeckId)
        if (newCards.isNotEmpty()) cardDao.upsertAll(newCards)
        return "Imported ${noteIdMap.size} notes and ${newCards.size} cards"
    }

    private fun readCollectionHeader(db: SQLiteDatabase): Pair<JSONObject, JSONObject> =
        db.rawQuery("SELECT models, decks FROM col LIMIT 1", null).use { c ->
            if (!c.moveToFirst()) throw ApkgFormatException("Empty collection")
            JSONObject(c.getString(0)) to JSONObject(c.getString(1))
        }

    private data class RawCard(val id: Long, val nid: Long, val did: Long, val ord: Int)

    private fun readRawCards(db: SQLiteDatabase): List<RawCard> {
        // Raw cards first, so we know which decks are actually used.
        val rawCards = mutableListOf<RawCard>()
        db.rawQuery("SELECT id, nid, did, ord FROM cards", null).use { c ->
            while (c.moveToNext()) {
                rawCards.add(
                    RawCard(
                        c.getLong(COL_ID),
                        c.getLong(COL_NID),
                        c.getLong(COL_DID),
                        c.getInt(COL_ORD),
                    )
                )
            }
        }
        return rawCards
    }

    private suspend fun importNotes(
        db: SQLiteDatabase,
        rawCards: List<RawCard>,
        modelIdMap: Map<Long, Long>,
        deckIdMap: Map<Long, Long>,
        fallbackDeckId: Long,
        noteDao: NoteDao,
    ): Map<Long, Long> {
        val nidToDid = rawCards.associate { it.nid to it.did }
        // Notes.
        val noteIdMap = HashMap<Long, Long>()
        val positionByDeck = HashMap<Long, Double>()
        db.rawQuery("SELECT id, guid, mid, tags, flds, mod FROM notes", null).use { c ->
            while (c.moveToNext()) {
                val oldId = c.getLong(0)
                val guid = c.getString(1) ?: FlashcardsViewModel.randomGuid()
                val mid = c.getLong(2)
                val tags = c.getString(3)?.trim().orEmpty()
                val fldsHtml = c.getString(4) ?: ""
                val mod = c.getLong(5)
                val noteTypeId = modelIdMap[mid] ?: continue
                val deckId = deckIdMap[nidToDid[oldId]] ?: fallbackDeckId
                val fieldsMd = fldsHtml.split("\u001f").map { HtmlConvert.htmlToMarkdown(it) }
                val position = positionByDeck.getOrDefault(deckId, 0.0) + 1.0
                positionByDeck[deckId] = position
                val note = Note(
                    noteTypeId = noteTypeId,
                    deckId = deckId,
                    guid = guid,
                    flds = fieldsMd.joinToString("\u001f"),
                    sortField = fieldsMd.firstOrNull().orEmpty(),
                    tags = tags,
                    mod = mod,
                    position = position,
                )
                val newId = noteDao.upsert(note)
                noteIdMap[oldId] = newId
            }
        }
        return noteIdMap
    }

    private fun buildCards(
        rawCards: List<RawCard>,
        noteIdMap: Map<Long, Long>,
        deckIdMap: Map<Long, Long>,
        fallbackDeckId: Long,
    ): List<Card> {
        // Cards (as new).
        val cardPositionByDeck = HashMap<Long, Double>()
        return rawCards.mapNotNull { raw ->
            val noteId = noteIdMap[raw.nid] ?: return@mapNotNull null
            val deckId = deckIdMap[raw.did] ?: fallbackDeckId
            val position = cardPositionByDeck.getOrDefault(deckId, 0.0) + 1.0
            cardPositionByDeck[deckId] = position
            Card(noteId = noteId, templateOrd = raw.ord, deckId = deckId, position = position)
        }
    }

    private suspend fun importNoteTypes(
        modelsJson: JSONObject,
        noteTypeDao: NoteTypeDao,
        fieldDao: NoteTypeFieldDao,
        templateDao: CardTemplateDao,
    ): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        val keys = modelsJson.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val model = modelsJson.getJSONObject(key)
            val oldId = key.toLongOrNull() ?: model.optLong("id")
            val type = model.optInt("type", 0)
            val newId = noteTypeDao.upsert(
                NoteType(
                    name = model.optString("name", "Imported"),
                    type = type,
                    css = model.optString("css", ""),
                    mod = model.optLong("mod", 0),
                ),
            )
            map[oldId] = newId

            val fldArray = model.getJSONArray("flds")
            val fields = (0 until fldArray.length()).map { i ->
                val fld = fldArray.getJSONObject(i)
                fld.optInt("ord", i) to fld.optString("name", "Field $i")
            }.sortedBy { it.first }
            fieldDao.upsertAll(
                fields.mapIndexed { ord, (_, name) -> NoteTypeField(noteTypeId = newId, ord = ord, name = name) },
            )

            val tmplArray = model.getJSONArray("tmpls")
            val templates = (0 until tmplArray.length()).map { i ->
                val tmpl = tmplArray.getJSONObject(i)
                Triple(
                    tmpl.optInt("ord", i),
                    tmpl.optString("name", "Card ${i + 1}"),
                    HtmlConvert.htmlTemplateToMarkdown(tmpl.optString("qfmt", "")) to
                        HtmlConvert.htmlTemplateToMarkdown(tmpl.optString("afmt", "")),
                )
            }.sortedBy { it.first }
            templateDao.upsertAll(
                templates.mapIndexed { ord, (_, name, fmt) ->
                    CardTemplate(noteTypeId = newId, ord = ord, name = name, qfmt = fmt.first, afmt = fmt.second)
                },
            )
        }
        return map
    }

    private suspend fun importDecks(
        usedDids: Set<Long>,
        decksJson: JSONObject,
        deckDao: DeckDao,
    ): Map<Long, Long> {
        val map = HashMap<Long, Long>()
        usedDids.forEach { did ->
            val name = decksJson.optJSONObject(did.toString())?.optString("name")
                ?.takeIf { it.isNotBlank() }
                ?: "Imported"
            map[did] = deckDao.upsert(Deck(name = name))
        }
        return map
    }

    /** Unzips [zip] into [targetDir], rejecting entries that escape the directory. */
    private fun unzipGuarded(zip: File, targetDir: File) {
        val canonicalTarget = targetDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(canonicalTarget + File.separator) &&
                    outFile.canonicalPath != canonicalTarget
                ) {
                    throw ApkgFormatException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
