package com.vayunmathur.flashcards.platform
import com.vayunmathur.flashcards.domain.HtmlConvert
import com.vayunmathur.flashcards.ui.NoteTypeWithConfig

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.vayunmathur.flashcards.data.Card
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.data.NoteTypeKind
import com.vayunmathur.library.util.BackupHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Writes a legacy **schema-11** `.apkg` (a zip of a plain `collection.anki2`
 * SQLite database plus an empty `media` manifest). Cards are exported as *new*
 * (Anki's SM-2 scheduling differs from our FSRS, so transferring it would be
 * lossy); media is dropped (text-only app).
 */
object ApkgExport {

    /** Builds the `.apkg` for the given data and returns the shareable file. */
    fun write(
        context: Context,
        name: String,
        decks: List<Deck>,
        notes: List<Note>,
        cards: List<Card>,
        noteTypes: List<NoteTypeWithConfig>,
    ): File {
        val work = File(context.cacheDir, "apkg_export_${System.currentTimeMillis()}").apply {
            deleteRecursively(); mkdirs()
        }
        val anki2 = File(work, "collection.anki2")
        val db = SQLiteDatabase.openOrCreateDatabase(anki2, null)
        try {
            createSchema(db)
            writeCollectionRow(db, decks, noteTypes)
            writeNotes(db, notes, noteTypes)
            writeCards(db, cards)
        } finally {
            db.close()
        }

        // Copy referenced images as numbered entries and build the media manifest.
        val mediaStore = MediaStore(context)
        val referenced = notes.flatMap { MediaStore.referenced(it.flds) }.distinct()
        val mediaMap = JSONObject()
        val mediaFiles = mutableListOf<File>()
        var mediaIndex = 0
        referenced.forEach { fileName ->
            val src = mediaStore.resolve(fileName)
            if (src.exists()) {
                val dest = File(work, mediaIndex.toString())
                src.copyTo(dest, overwrite = true)
                mediaMap.put(mediaIndex.toString(), fileName)
                mediaFiles.add(dest)
                mediaIndex++
            }
        }
        File(work, "media").writeText(mediaMap.toString())

        val outDir = File(context.cacheDir, "shared_decks").apply { mkdirs() }
        val safeName = name.ifBlank { "collection" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val apkg = File(outDir, "$safeName.apkg")
        if (apkg.exists()) apkg.delete()
        apkg.outputStream().use { out ->
            BackupHelper.zipFiles(
                listOf(File(work, "collection.anki2"), File(work, "media")) + mediaFiles,
                work,
                out,
            )
        }
        work.deleteRecursively()
        return apkg
    }

    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, " +
                "scm integer not null, ver integer not null, dty integer not null, usn integer not null, " +
                "ls integer not null, conf text not null, models text not null, decks text not null, " +
                "dconf text not null, tags text not null)",
        )
        db.execSQL(
            "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, " +
                "mod integer not null, usn integer not null, tags text not null, flds text not null, " +
                "sfld integer not null, csum integer not null, flags integer not null, data text not null)",
        )
        db.execSQL(
            "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, " +
                "ord integer not null, mod integer not null, usn integer not null, type integer not null, " +
                "queue integer not null, due integer not null, ivl integer not null, factor integer not null, " +
                "reps integer not null, lapses integer not null, left integer not null, odue integer not null, " +
                "odid integer not null, flags integer not null, data text not null)",
        )
        db.execSQL(
            "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, " +
                "ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, " +
                "time integer not null, type integer not null)",
        )
        db.execSQL("CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)")
        db.execSQL("CREATE INDEX ix_notes_csum on notes (csum)")
        db.execSQL("CREATE INDEX ix_cards_nid on cards (nid)")
        db.execSQL("CREATE INDEX ix_cards_sched on cards (did, queue, due)")
        db.execSQL("CREATE INDEX ix_revlog_cid on revlog (cid)")
    }

    private fun writeCollectionRow(
        db: SQLiteDatabase,
        decks: List<Deck>,
        noteTypes: List<NoteTypeWithConfig>,
    ) {
        val now = System.currentTimeMillis()
        val crt = now / 1000
        val defaultDeckId = decks.firstOrNull()?.id ?: 1L
        val models = buildModelsJson(noteTypes, defaultDeckId)
        val decksJson = buildDecksJson(decks, crt)
        val conf = buildConfJson(decks, noteTypes)
        val dconf = buildDconfJson()
        val values = ContentValues().apply {
            put("id", 1)
            put("crt", crt)
            put("mod", now)
            put("scm", now)
            put("ver", 11)
            put("dty", 0)
            put("usn", 0)
            put("ls", 0)
            put("conf", conf.toString())
            put("models", models.toString())
            put("decks", decksJson.toString())
            put("dconf", dconf.toString())
            put("tags", "{}")
        }
        db.insert("col", null, values)
    }

    private fun buildModelsJson(noteTypes: List<NoteTypeWithConfig>, defaultDeckId: Long): JSONObject {
        val models = JSONObject()
        noteTypes.forEach { cfg ->
            models.put(cfg.noteType.id.toString(), buildModelJson(cfg, defaultDeckId))
        }
        return models
    }

    private fun buildModelJson(cfg: NoteTypeWithConfig, defaultDeckId: Long): JSONObject {
        val model = JSONObject()
        model.put("id", cfg.noteType.id)
        model.put("name", cfg.noteType.name)
        model.put("type", cfg.noteType.type)
        model.put("mod", cfg.noteType.mod)
        model.put("usn", -1)
        model.put("sortf", 0)
        model.put("did", defaultDeckId)
        model.put("css", cfg.noteType.css)
        model.put("latexPre", "")
        model.put("latexPost", "")
        model.put("flds", buildFieldsJson(cfg))
        model.put("tmpls", buildTemplatesJson(cfg))
        model.put("req", buildReqJson(cfg))
        return model
    }

    private fun buildFieldsJson(cfg: NoteTypeWithConfig): JSONArray {
        val flds = JSONArray()
        cfg.fields.forEachIndexed { ord, field ->
            flds.put(
                JSONObject().apply {
                    put("name", field.name)
                    put("ord", ord)
                    put("sticky", false)
                    put("rtl", false)
                    put("font", "Arial")
                    put("size", DEFAULT_FONT_SIZE)
                    put("media", JSONArray())
                },
            )
        }
        return flds
    }

    private fun buildTemplatesJson(cfg: NoteTypeWithConfig): JSONArray {
        val tmpls = JSONArray()
        cfg.templates.forEachIndexed { ord, tpl ->
            tmpls.put(
                JSONObject().apply {
                    put("name", tpl.name)
                    put("ord", ord)
                    put("qfmt", HtmlConvert.markdownTemplateToHtml(tpl.qfmt))
                    put("afmt", HtmlConvert.markdownTemplateToHtml(tpl.afmt))
                    put("did", JSONObject.NULL)
                    put("bqfmt", "")
                    put("bafmt", "")
                },
            )
        }
        return tmpls
    }

    private fun buildReqJson(cfg: NoteTypeWithConfig): JSONArray {
        val req = JSONArray()
        if (cfg.noteType.type == NoteTypeKind.STANDARD) {
            val fieldOrds = JSONArray().apply { cfg.fields.indices.forEach { put(it) } }
            cfg.templates.indices.forEach { ord ->
                req.put(JSONArray().apply { put(ord); put("any"); put(fieldOrds) })
            }
        }
        return req
    }

    private fun buildDecksJson(decks: List<Deck>, crt: Long): JSONObject {
        val decksJson = JSONObject()
        val deckList = decks.ifEmpty { listOf(Deck(id = 1, name = "Default")) }
        deckList.forEach { deck ->
            decksJson.put(
                deck.id.toString(),
                JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                    put("mod", crt)
                    put("usn", -1)
                    put("lrnToday", JSONArray(listOf(0, 0)))
                    put("revToday", JSONArray(listOf(0, 0)))
                    put("newToday", JSONArray(listOf(0, 0)))
                    put("timeToday", JSONArray(listOf(0, 0)))
                    put("conf", 1)
                    put("desc", "")
                    put("dyn", 0)
                    put("collapsed", false)
                    put("extendNew", 0)
                    put("extendRev", 0)
                },
            )
        }
        return decksJson
    }

    private fun buildConfJson(decks: List<Deck>, noteTypes: List<NoteTypeWithConfig>): JSONObject {
        val deckList = decks.ifEmpty { listOf(Deck(id = 1, name = "Default")) }
        return JSONObject().apply {
            put("nextPos", 1)
            put("estTimes", true)
            put("activeDecks", JSONArray(listOf(deckList.first().id)))
            put("sortType", "noteFld")
            put("timeLim", 0)
            put("sortBackwards", false)
            put("addToCur", true)
            put("curDeck", deckList.first().id)
            put("newSpread", 0)
            put("dueCounts", true)
            put("curModel", noteTypes.firstOrNull()?.noteType?.id?.toString() ?: "1")
            put("collapseTime", COLLAPSE_TIME_MINUTES)
        }
    }

    private fun buildDconfJson(): JSONObject {
        val dconf = JSONObject().apply {
            put(
                "1",
                JSONObject().apply {
                    put("id", 1)
                    put("name", "Default")
                    put("mod", 0)
                    put("usn", 0)
                    put("maxTaken", 60)
                    put("autoplay", true)
                    put("timer", 0)
                    put("replayq", true)
                    put(
                        "new",
                        JSONObject().apply {
                            put("bury", false)
                            put("delays", JSONArray(listOf(1, 10)))
                            put("initialFactor", 2500)
                            put("ints", JSONArray(listOf(1, 4, 0)))
                            put("order", 1)
                            put("perDay", 20)
                        },
                    )
                    put(
                        "rev",
                        JSONObject().apply {
                            put("bury", false)
                            put("ease4", 1.3)
                            put("ivlFct", 1.0)
                            put("maxIvl", 36500)
                            put("perDay", 200)
                            put("hardFactor", 1.2)
                        },
                    )
                    put(
                        "lapse",
                        JSONObject().apply {
                            put("delays", JSONArray(listOf(10)))
                            put("leechAction", 1)
                            put("leechFails", 8)
                            put("minInt", 1)
                            put("mult", 0.0)
                        },
                    )
                    put("dyn", false)
                },
            )
        }
        return dconf
    }

    private fun writeNotes(
        db: SQLiteDatabase,
        notes: List<Note>,
        noteTypes: List<NoteTypeWithConfig>,
    ) {
        val now = System.currentTimeMillis() / 1000
        notes.forEach { note ->
            val cfg = noteTypes.firstOrNull { it.noteType.id == note.noteTypeId }
            val fieldMd = note.fieldList
            val fieldHtml = fieldMd.map { HtmlConvert.markdownFieldToHtml(it) }
            val flds = fieldHtml.joinToString("\u001f")
            val sfld = stripHtml(fieldHtml.firstOrNull() ?: note.sortField)
            val values = ContentValues().apply {
                put("id", note.id)
                put("guid", note.guid.ifBlank { FlashcardsViewModel.randomGuid() })
                put("mid", note.noteTypeId)
                put("mod", if (note.mod > 0) note.mod else now)
                put("usn", -1)
                put("tags", if (note.tags.isBlank()) "" else " ${note.tags.trim()} ")
                put("flds", flds)
                put("sfld", sfld)
                put("csum", fieldChecksum(sfld))
                put("flags", 0)
                put("data", "")
            }
            db.insert("notes", null, values)
            // cfg is only needed for validation; suppress unused warning.
            cfg?.let { }
        }
    }

    private fun writeCards(db: SQLiteDatabase, cards: List<Card>) {
        val now = System.currentTimeMillis() / 1000
        cards.forEach { card ->
            val values = ContentValues().apply {
                put("id", card.id)
                put("nid", card.noteId)
                put("did", card.deckId)
                put("ord", card.templateOrd)
                put("mod", now)
                put("usn", -1)
                put("type", 0)
                put("queue", 0)
                put("due", (card.position.toLong()).coerceAtLeast(1))
                put("ivl", 0)
                put("factor", 0)
                put("reps", 0)
                put("lapses", 0)
                put("left", 0)
                put("odue", 0)
                put("odid", 0)
                put("flags", 0)
                put("data", "")
            }
            db.insert("cards", null, values)
        }
    }

    private fun stripHtml(html: String): String =
        Regex("<[^>]+>").replace(html, "").trim()

    /** Anki's field checksum: the first 8 hex digits of sha1(sfld), as an integer. */
    private fun fieldChecksum(sfld: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(sfld.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, CHECKSUM_HEX_DIGITS).toLong(HEX_RADIX)
    }

    private const val DEFAULT_FONT_SIZE = 20
    private const val COLLAPSE_TIME_MINUTES = 1200
    private const val CHECKSUM_HEX_DIGITS = 8
    private const val HEX_RADIX = 16
}
