package com.vayunmathur.flashcards.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.DatabaseMigrations
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "flashcards-db"

/** Backup config shared by [AppBackupAgent] and the in-app backup buttons. */
fun flashcardsDbConfigs(context: Context): List<Pair<String, String>> =
    listOf(DB_NAME to DatabaseHelper(context).getPassphrase())

@Dao
interface DeckDao {
    @Query("SELECT * FROM Deck")
    fun getAllFlow(): Flow<List<Deck>>

    @Query("SELECT * FROM Deck")
    suspend fun getAll(): List<Deck>

    @Query("SELECT * FROM Deck WHERE id = :id")
    suspend fun getById(id: Long): Deck?

    @Upsert
    suspend fun upsert(value: Deck): Long

    @Delete
    suspend fun delete(value: Deck): Int
}

@Dao
interface NoteTypeDao {
    @Query("SELECT * FROM NoteType")
    fun getAllFlow(): Flow<List<NoteType>>

    @Query("SELECT * FROM NoteType")
    suspend fun getAll(): List<NoteType>

    @Query("SELECT * FROM NoteType WHERE id = :id")
    suspend fun getById(id: Long): NoteType?

    @Upsert
    suspend fun upsert(value: NoteType): Long

    @Delete
    suspend fun delete(value: NoteType): Int
}

@Dao
interface NoteTypeFieldDao {
    @Query("SELECT * FROM NoteTypeField")
    fun getAllFlow(): Flow<List<NoteTypeField>>

    @Query("SELECT * FROM NoteTypeField")
    suspend fun getAll(): List<NoteTypeField>

    @Query("SELECT * FROM NoteTypeField WHERE noteTypeId = :noteTypeId ORDER BY ord")
    suspend fun getByNoteType(noteTypeId: Long): List<NoteTypeField>

    @Upsert
    suspend fun upsert(value: NoteTypeField): Long

    @Upsert
    suspend fun upsertAll(values: List<NoteTypeField>)

    @Query("DELETE FROM NoteTypeField WHERE noteTypeId = :noteTypeId")
    suspend fun deleteByNoteType(noteTypeId: Long)
}

@Dao
interface CardTemplateDao {
    @Query("SELECT * FROM CardTemplate")
    fun getAllFlow(): Flow<List<CardTemplate>>

    @Query("SELECT * FROM CardTemplate")
    suspend fun getAll(): List<CardTemplate>

    @Query("SELECT * FROM CardTemplate WHERE noteTypeId = :noteTypeId ORDER BY ord")
    suspend fun getByNoteType(noteTypeId: Long): List<CardTemplate>

    @Upsert
    suspend fun upsert(value: CardTemplate): Long

    @Upsert
    suspend fun upsertAll(values: List<CardTemplate>)

    @Query("DELETE FROM CardTemplate WHERE noteTypeId = :noteTypeId")
    suspend fun deleteByNoteType(noteTypeId: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM Note")
    fun getAllFlow(): Flow<List<Note>>

    @Query("SELECT * FROM Note")
    suspend fun getAll(): List<Note>

    @Query("SELECT * FROM Note WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<Note>>

    @Query("SELECT * FROM Note WHERE deckId = :deckId")
    suspend fun getByDeck(deckId: Long): List<Note>

    @Query("SELECT * FROM Note WHERE noteTypeId = :noteTypeId")
    suspend fun getByNoteType(noteTypeId: Long): List<Note>

    @Query("SELECT * FROM Note WHERE id = :id")
    suspend fun getById(id: Long): Note?

    @Query("SELECT * FROM Note WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Note>

    @Query("SELECT * FROM Note WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Note?>

    @Upsert
    suspend fun upsert(value: Note): Long

    @Upsert
    suspend fun upsertAll(values: List<Note>)

    @Delete
    suspend fun delete(value: Note): Int

    @Query("DELETE FROM Note WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM Note WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)
}

@Dao
interface CardDao {
    @Query("SELECT * FROM Card")
    fun getAllFlow(): Flow<List<Card>>

    @Query("SELECT * FROM Card")
    suspend fun getAll(): List<Card>

    @Query("SELECT * FROM Card WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE deckId = :deckId")
    suspend fun getByDeck(deckId: Long): List<Card>

    @Query("SELECT * FROM Card WHERE deckId = :deckId AND dueDate <= :now AND suspended = 0")
    fun getDueByDeckFlow(deckId: Long, now: Long): Flow<List<Card>>

    @Query("SELECT * FROM Card WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Card?>

    @Query("SELECT * FROM Card WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Card>

    @Query("SELECT * FROM Card WHERE noteId = :noteId")
    suspend fun getByNote(noteId: Long): List<Card>

    @Query("SELECT * FROM Card WHERE noteId IN (:noteIds)")
    suspend fun getByNotes(noteIds: List<Long>): List<Card>

    @Upsert
    suspend fun upsert(value: Card): Long

    @Upsert
    suspend fun upsertAll(values: List<Card>)

    @Delete
    suspend fun delete(value: Card): Int

    @Query("UPDATE Card SET suspended = :value WHERE id IN (:ids)")
    suspend fun setSuspended(ids: List<Long>, value: Int)

    @Query("UPDATE Card SET suspended = :value WHERE noteId IN (:noteIds)")
    suspend fun setSuspendedByNotes(noteIds: List<Long>, value: Int)

    @Query("UPDATE Card SET deckId = :deckId WHERE noteId IN (:noteIds)")
    suspend fun moveByNotes(noteIds: List<Long>, deckId: Long)

    @Query("DELETE FROM Card WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)

    @Query("DELETE FROM Card WHERE noteId = :noteId")
    suspend fun deleteByNote(noteId: Long)

    @Query("DELETE FROM Card WHERE noteId IN (:noteIds)")
    suspend fun deleteByNotes(noteIds: List<Long>)

    @Query("DELETE FROM Card WHERE noteId = :noteId AND templateOrd NOT IN (:keepOrds)")
    suspend fun deleteRemovedTemplates(noteId: Long, keepOrds: List<Int>)
}

@Dao
interface ReviewLogDao {
    @Query("SELECT * FROM ReviewLog")
    fun getAllFlow(): Flow<List<ReviewLog>>

    @Query("SELECT * FROM ReviewLog WHERE deckId = :deckId")
    fun getByDeckFlow(deckId: Long): Flow<List<ReviewLog>>

    @Query("SELECT * FROM ReviewLog WHERE deckId = :deckId ORDER BY cardId, reviewedAt")
    suspend fun getByDeckOrdered(deckId: Long): List<ReviewLog>

    @Query("SELECT * FROM ReviewLog WHERE cardId = :cardId")
    suspend fun getByCard(cardId: Long): List<ReviewLog>

    @Insert
    suspend fun insert(value: ReviewLog): Long

    @Query("DELETE FROM ReviewLog WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM ReviewLog WHERE cardId IN (:cardIds)")
    suspend fun deleteByCards(cardIds: List<Long>)

    @Query("UPDATE ReviewLog SET deckId = :deckId WHERE cardId IN (SELECT id FROM Card WHERE noteId IN (:noteIds))")
    suspend fun moveByNotes(noteIds: List<Long>, deckId: Long)

    @Query("DELETE FROM ReviewLog WHERE deckId = :deckId")
    suspend fun deleteByDeck(deckId: Long)
}

@Database(
    entities = [
        Deck::class,
        Card::class,
        ReviewLog::class,
        NoteType::class,
        NoteTypeField::class,
        CardTemplate::class,
        Note::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class FlashcardsDatabase : RoomDatabase() {
    abstract fun deckDao(): DeckDao
    abstract fun cardDao(): CardDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun noteTypeDao(): NoteTypeDao
    abstract fun noteTypeFieldDao(): NoteTypeFieldDao
    abstract fun cardTemplateDao(): CardTemplateDao
    abstract fun noteDao(): NoteDao

    companion object : DatabaseMigrations {
        override val migrations = listOf(
            Migration(1, 2) { db ->
                // FSRS + content columns on Card. DEFAULTs populate legacy rows.
                db.execSQL("ALTER TABLE Card ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Card ADD COLUMN stability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN difficulty REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN state INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN lastReview INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN lapses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Card ADD COLUMN reps INTEGER NOT NULL DEFAULT 0")
                // Per-deck study config.
                db.execSQL("ALTER TABLE Deck ADD COLUMN newPerDay INTEGER NOT NULL DEFAULT 20")
                db.execSQL("ALTER TABLE Deck ADD COLUMN maxReviewsPerDay INTEGER NOT NULL DEFAULT 200")
                db.execSQL("ALTER TABLE Deck ADD COLUMN desiredRetention REAL NOT NULL DEFAULT 0.9")
                // Review history.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ReviewLog (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "cardId INTEGER NOT NULL, " +
                        "deckId INTEGER NOT NULL, " +
                        "reviewedAt INTEGER NOT NULL, " +
                        "grade INTEGER NOT NULL, " +
                        "elapsedDays REAL NOT NULL, " +
                        "scheduledDays REAL NOT NULL, " +
                        "state INTEGER NOT NULL)",
                )
            },
            MIGRATION_2_3,
            MIGRATION_3_4,
        )
    }
}

/**
 * Restructures the flat `Card(front, back, tags, …)` schema into Anki's
 * NoteType / Note / Card model.
 *
 * The DDL below is copied verbatim from Room's generated
 * `schemas/…/FlashcardsDatabase/3.json` `createSql` so it byte-matches what Room
 * validates on open. Each old card becomes a Basic [Note] (id preserved so
 * [ReviewLog.cardId] references stay valid) plus one [Card] pointing at it.
 */
val MIGRATION_2_3 = Migration(2, 3) { db ->
    // 1. New model tables + indices (Room's exact generated DDL).
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `NoteType` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`type` INTEGER NOT NULL, " +
            "`css` TEXT NOT NULL, " +
            "`mod` INTEGER NOT NULL)",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `NoteTypeField` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`noteTypeId` INTEGER NOT NULL, " +
            "`ord` INTEGER NOT NULL, " +
            "`name` TEXT NOT NULL)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_NoteTypeField_noteTypeId_ord` " +
            "ON `NoteTypeField` (`noteTypeId`, `ord`)",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `CardTemplate` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`noteTypeId` INTEGER NOT NULL, " +
            "`ord` INTEGER NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`qfmt` TEXT NOT NULL, " +
            "`afmt` TEXT NOT NULL)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_CardTemplate_noteTypeId_ord` " +
            "ON `CardTemplate` (`noteTypeId`, `ord`)",
    )
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `Note` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`noteTypeId` INTEGER NOT NULL, " +
            "`deckId` INTEGER NOT NULL, " +
            "`guid` TEXT NOT NULL, " +
            "`flds` TEXT NOT NULL, " +
            "`sortField` TEXT NOT NULL, " +
            "`tags` TEXT NOT NULL, " +
            "`mod` INTEGER NOT NULL, " +
            "`position` REAL NOT NULL)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_Note_noteTypeId` ON `Note` (`noteTypeId`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_Note_deckId` ON `Note` (`deckId`)")

    // 2. Seed built-in note types with fixed ids.
    db.execSQL(
        "INSERT INTO `NoteType` (`id`, `name`, `type`, `css`, `mod`) VALUES " +
            "(1, 'Basic', 0, '', 0), " +
            "(2, 'Basic (and reversed card)', 0, '', 0), " +
            "(3, 'Cloze', 1, '', 0)",
    )
    db.execSQL(
        "INSERT INTO `NoteTypeField` (`noteTypeId`, `ord`, `name`) VALUES " +
            "(1, 0, 'Front'), (1, 1, 'Back'), " +
            "(2, 0, 'Front'), (2, 1, 'Back'), " +
            "(3, 0, 'Text'), (3, 1, 'Back Extra')",
    )
    db.execSQL(
        "INSERT INTO `CardTemplate` (`noteTypeId`, `ord`, `name`, `qfmt`, `afmt`) VALUES " +
            "(1, 0, 'Card 1', '{{Front}}', '{{FrontSide}}" + "\n\n---\n\n" + "{{Back}}'), " +
            "(2, 0, 'Card 1', '{{Front}}', '{{FrontSide}}" + "\n\n---\n\n" + "{{Back}}'), " +
            "(2, 1, 'Card 2', '{{Back}}', '{{FrontSide}}" + "\n\n---\n\n" + "{{Front}}'), " +
            "(3, 0, 'Cloze', '{{cloze:Text}}', '{{cloze:Text}}" + "\n\n---\n\n" + "{{Back Extra}}')",
    )

    // 3. Convert each old Card into a Basic Note (noteTypeId 1). Note.id = old
    //    Card.id so ReviewLog.cardId references stay valid.
    db.execSQL(
        "INSERT INTO `Note` (`id`, `noteTypeId`, `deckId`, `guid`, `flds`, `sortField`, `tags`, `mod`, `position`) " +
            "SELECT id, 1, deckId, lower(hex(randomblob(8))), front || char(31) || back, " +
            "front, tags, 0, position FROM Card",
    )

    // 4. Rebuild Card with the new shape, one card per old card (templateOrd 0).
    db.execSQL("ALTER TABLE `Card` RENAME TO `Card_old`")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `Card` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`noteId` INTEGER NOT NULL, " +
            "`templateOrd` INTEGER NOT NULL, " +
            "`deckId` INTEGER NOT NULL, " +
            "`stability` REAL NOT NULL, " +
            "`difficulty` REAL NOT NULL, " +
            "`state` INTEGER NOT NULL, " +
            "`lastReview` INTEGER NOT NULL, " +
            "`lapses` INTEGER NOT NULL, " +
            "`reps` INTEGER NOT NULL, " +
            "`dueDate` INTEGER NOT NULL, " +
            "`easeFactor` REAL NOT NULL, " +
            "`intervalDays` INTEGER NOT NULL, " +
            "`repetitions` INTEGER NOT NULL, " +
            "`position` REAL NOT NULL)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_Card_noteId_templateOrd` " +
            "ON `Card` (`noteId`, `templateOrd`)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_Card_deckId` ON `Card` (`deckId`)")
    db.execSQL(
        "INSERT INTO `Card` (`id`, `noteId`, `templateOrd`, `deckId`, `stability`, `difficulty`, " +
            "`state`, `lastReview`, `lapses`, `reps`, `dueDate`, `easeFactor`, `intervalDays`, " +
            "`repetitions`, `position`) " +
            "SELECT id, id, 0, deckId, stability, difficulty, state, lastReview, lapses, reps, " +
            "dueDate, easeFactor, intervalDays, repetitions, position FROM Card_old",
    )
    db.execSQL("DROP TABLE `Card_old`")
}

/**
 * Adds suspend/leech + per-deck FSRS columns. Plain `ALTER TABLE ADD COLUMN`s with
 * defaults so legacy rows populate correctly; the entity fields carry no
 * `@ColumnInfo(defaultValue=…)`, so Room skips the default-value comparison on open
 * (same pattern as [MIGRATION_2_3]).
 */
val MIGRATION_3_4 = Migration(3, 4) { db ->
    db.execSQL("ALTER TABLE Card ADD COLUMN suspended INTEGER NOT NULL DEFAULT 0")
    db.execSQL("ALTER TABLE Deck ADD COLUMN fsrsWeights TEXT NOT NULL DEFAULT ''")
    db.execSQL("ALTER TABLE Deck ADD COLUMN leechThreshold INTEGER NOT NULL DEFAULT 8")
}
