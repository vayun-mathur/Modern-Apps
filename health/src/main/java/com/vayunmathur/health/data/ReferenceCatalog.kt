package com.vayunmathur.health.data

import android.content.Context
import android.os.storage.StorageManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.loadSqlCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.brotli.dec.BrotliInputStream
import java.io.File
import java.io.IOException

/**
 * The medical terminologies the add-record pickers search, shipped inside the APK.
 *
 * Two catalogues in one SQLite file, built by `scripts/generate_med_db.py`:
 *
 *  - **medications** — ~21,000 prescribable drug products from RxNorm Current Prescribable Content,
 *    each with the RXCUI that FHIR's `medicationCodeableConcept` wants, plus the ~5,800 ingredients
 *    behind them for coding drug allergies.
 *  - **conditions** — ~75,000 ICD-10-CM codes for `Condition.code`, stored dotted.
 *  - **labs** — ~62,000 LOINC laboratory codes for `Observation.code`, ranked by how commonly the
 *    test is ordered.
 *
 * One file rather than three so there is a single asset to unpack and a single schema version to
 * check. No picker makes a network request; the downloads happen at build time.
 *
 * RxNorm Current Prescribable Content and ICD-10-CM are public domain. This content LOINC® is
 * copyright © 1995-2024 Regenstrief Institute, Inc. and the LOINC Committee, available at no cost
 * under the licence at http://loinc.org/license.
 *
 * Deliberately much simpler than [com.vayunmathur.health.data.FoodDatabase], which ships a columnar
 * file and rebuilds SQLite on device. That trade only pays at Open Food Facts' scale — here the
 * container and indexes compress to little more than the raw data, so this ships a finished database
 * and only has to decompress it. Everything else is the same: expanded into `filesDir` once, opened
 * read-only through SQLCipher's bundled SQLite (which guarantees a known FTS5) with an empty
 * passphrase, and written through a `.part` file so a failure midway cannot leave a half-built
 * database in place.
 *
 * When the asset is absent — a clean checkout where the generator has not been run — [status] stays
 * [Status.Absent] and every picker falls back to free-text entry rather than failing. The lab table
 * is absent more often than the others, since LOINC cannot be downloaded without a login.
 */
object ReferenceCatalog {

    private const val TAG = "ReferenceCatalog"
    private const val ASSET_DB = "catalog.db.br"
    private const val ASSET_META = "catalog.db.meta.json"
    private const val DB_FILE = "catalog.db"
    private const val PART_DB = "catalog.db.part"
    private const val META_FILE = "catalog.db.meta.json"

    /** Schema this build can read. Bumped in lockstep with `generate_med_db.py`. */
    private const val SUPPORTED_SCHEMA_VERSION = 4

    /** The unpacked database runs about twice the asset size; reserve that up front. */
    private const val DB_SIZE_FACTOR = 2

    /** Brotli decoder window and stream-copy buffer. */
    private const val BROTLI_BUFFER = 64 * 1024
    private const val COPY_BUFFER = 256 * 1024

    /** Cursor columns of the medication SELECT, in order. */
    private const val MED_COLUMN_STRENGTH = 3
    private const val MED_COLUMN_DOSE_FORM = 4

    /** Describes the bundled asset; emitted next to it by the generator. */
    @Serializable
    data class Meta(
        val schemaVersion: Int = 0,
        val medications: Long = 0,
        val ingredients: Long = 0,
        val conditions: Long = 0,
        val labs: Long = 0,
        /** Unpacked size, used for the free-space check. */
        val bytes: Long = 0,
        val compressedBytes: Long = 0,
        /** RxNorm release the asset was built from, e.g. "2026-09-08". */
        val release: String = "",
        val icd10Year: Int = 0,
    )

    /** Contract for status. */
    sealed interface Status {
        val installed: Meta? get() = null

        /** Not unpacked yet; the pickers accept free text only. */
        data object Absent : Status

        /** Singleton holding preparing state and operations. */
        data object Preparing : Status

        /** Immutable value describing ready; carries meta. */
        data class Ready(val meta: Meta) : Status {
            override val installed: Meta get() = meta
        }

        /** Immutable value describing failed; carries message, installed. */
        data class Failed(val message: String, override val installed: Meta?) : Status
    }

    /** One prescribable drug product. */
    data class Medication(
        val rxcui: String,
        /** Full RxNorm name, e.g. "amoxicillin 500 MG Oral Capsule". */
        val name: String,
        /** Base ingredient, e.g. "amoxicillin". The first of the two picker steps. */
        val ingredient: String,
        /** Strength as printed in the name, e.g. "500 MG", or null when there is none. */
        val strength: String?,
        /** Dose form, e.g. "Oral Capsule", or null when there is none. */
        val doseForm: String?,
    )

    /** One ICD-10-CM diagnosis. */
    data class Condition(
        /** Dotted, e.g. "E11.9" — the form FHIR expects. */
        val code: String,
        val description: String,
    )

    /** One RxNorm ingredient, which is the level a drug allergy is recorded at. */
    data class Ingredient(val rxcui: String, val name: String)

    /** One LOINC laboratory test. */
    data class LabTest(
        val code: String,
        val name: String,
        /** Example UCUM unit LOINC suggests for the test, e.g. "mg/dL". */
        val unit: String?,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context

    private val prepareMutex = Mutex()

    /**
     * The unpacked database, once confirmed to match the asset in this process.
     *
     * Confirmation is what makes [prepare] cheap to call on every picker, and it deliberately
     * compares the whole descriptor rather than just [Meta.schemaVersion]. Two builds can share a
     * schema and hold different data — adding the LOINC table did exactly that — and short-cutting
     * on the version alone leaves the previous build's database in place forever.
     */
    @Volatile
    private var verified: Meta? = null

    private var handle: SQLiteDatabase? = null
    private val lock = Any()

    private val dbFile: File get() = File(appContext.filesDir, DB_FILE)
    private val partDbFile: File get() = File(appContext.filesDir, PART_DB)
    private val metaFile: File get() = File(appContext.filesDir, META_FILE)

    fun init(context: Context) {
        appContext = context.applicationContext
        loadSqlCipher()
        partDbFile.delete()
        // A schema bump renames nothing, so the previous version's files linger. Clear them once
        // rather than leaving a few megabytes of dead database in filesDir forever.
        File(appContext.filesDir, "rxterms.db").delete()
        File(appContext.filesDir, "rxterms.db.meta.json").delete()
    }

    /**
     * Unpack and open in the background, so the first picker the user opens does not have to.
     *
     * Unpacking is a ~14 MB brotli decompression and disk write, then opening a file of that size.
     * Half a second, once — but half a second the user would otherwise spend staring at an empty
     * search screen the first time they add a medication.
     */
    fun warmUp(scope: CoroutineScope) {
        scope.launch { runCatching { prepare() } }
    }

    // --- Medications -------------------------------------------------------

    /**
     * Distinct ingredients matching [query] — the first medication picker step.
     *
     * Ordered by how many products carry the ingredient, so common drugs surface first.
     */
    suspend fun searchIngredients(query: String): List<String> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT m.ingredient, COUNT(*) AS products
                FROM medications_fts fts
                JOIN medications m ON fts.rowid = m.rowid
                WHERE medications_fts MATCH ?
                GROUP BY m.ingredient
                ORDER BY products DESC, LENGTH(m.ingredient) ASC, m.ingredient ASC
                LIMIT 50
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Ingredient search failed", e)
            emptyList()
        }
    }

    /** Every product for one ingredient — the second picker step. */
    suspend fun productsFor(ingredient: String): List<Medication> = withContext(Dispatchers.IO) {
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT rxcui, name, ingredient, strength, dose_form
                FROM medications
                WHERE ingredient = ?
                ORDER BY dose_form ASC, LENGTH(name) ASC, name ASC
                LIMIT 500
                """.trimIndent(),
                arrayOf(ingredient),
            ).use { cursor -> cursor.readMedications() }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Product lookup failed", e)
            emptyList()
        }
    }

    /** Free-form product search, for users who would rather type the whole thing. */
    suspend fun searchProducts(query: String): List<Medication> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT m.rxcui, m.name, m.ingredient, m.strength, m.dose_form
                FROM medications_fts fts
                JOIN medications m ON fts.rowid = m.rowid
                WHERE medications_fts MATCH ?
                ORDER BY LENGTH(m.name) ASC, m.name ASC
                LIMIT 100
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor -> cursor.readMedications() }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Product search failed", e)
            emptyList()
        }
    }

    /**
     * RxNorm ingredients matching [query], for coding a drug allergy.
     *
     * Ingredients rather than products, because an allergy is to penicillin rather than to a
     * particular pack of 500 mg capsules.
     */
    suspend fun searchAllergens(query: String): List<Ingredient> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT i.rxcui, i.name
                FROM ingredients_fts fts
                JOIN ingredients i ON fts.rowid = i.rowid
                WHERE ingredients_fts MATCH ?
                ORDER BY LENGTH(i.name) ASC, i.name ASC
                LIMIT 50
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Ingredient(cursor.getString(0), cursor.getString(1)))
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Allergen search failed", e)
            emptyList()
        }
    }

    /**
     * LOINC laboratory tests matching [query].
     *
     * Ranked by LOINC's own `COMMON_TEST_RANK` first, so the tests that make up almost all real lab
     * volume come before the long tail of research assays. Unranked codes sort as if ranked last
     * rather than being hidden — they are still valid answers, just unlikely ones.
     *
     * Matches against the hidden alias column as well as the display name, because LOINC spells
     * everything out: "ALT" and "HbA1c" appear only in the short and display names, never in the
     * long one that gets shown, so searching the visible text alone would miss the abbreviation
     * anybody would actually type.
     *
     * Empty when the build has no LOINC table: it cannot be downloaded without a login, so
     * `generate_med_db.py` leaves the table empty unless it was pointed at one, and the picker falls
     * back to free text.
     */
    suspend fun searchLabs(query: String): List<LabTest> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT l.code, l.name, l.unit
                FROM labs_fts fts JOIN labs l ON fts.rowid = l.rowid
                WHERE labs_fts MATCH ?
                ORDER BY (l.rank = 0), l.rank ASC, LENGTH(l.name) ASC, l.code ASC
                LIMIT 100
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            LabTest(
                                code = cursor.getString(0),
                                name = cursor.getString(1),
                                unit = if (cursor.isNull(2)) null else cursor.getString(2),
                            )
                        )
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Lab search failed", e)
            emptyList()
        }
    }

    // --- Conditions --------------------------------------------------------

    /**
     * ICD-10-CM codes matching [query].
     *
     * Shortest description first. ICD-10-CM is exhaustively specific — most of its 75,000 codes
     * describe an encounter type or a laterality of something rare — so the plain everyday
     * diagnosis is almost always the shortest string that matches.
     */
    suspend fun searchConditions(query: String): List<Condition> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()
        try {
            db.rawQuery(
                """
                SELECT c.code, c.description
                FROM conditions_fts fts
                JOIN conditions c ON fts.rowid = c.rowid
                WHERE conditions_fts MATCH ?
                ORDER BY LENGTH(c.description) ASC, c.code ASC
                LIMIT 100
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Condition(cursor.getString(0), cursor.getString(1)))
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Condition search failed", e)
            emptyList()
        }
    }

    private fun android.database.Cursor.readMedications(): List<Medication> = buildList {
        while (moveToNext()) {
            add(
                Medication(
                    rxcui = getString(0),
                    name = getString(1),
                    ingredient = getString(2),
                    strength = if (isNull(MED_COLUMN_STRENGTH)) {
                        null
                    } else {
                        getString(MED_COLUMN_STRENGTH)
                    },
                    doseForm = if (isNull(MED_COLUMN_DOSE_FORM)) {
                        null
                    } else {
                        getString(MED_COLUMN_DOSE_FORM)
                    },
                )
            )
        }
    }

    /**
     * Turn raw user input into an FTS5 expression that matches what the user has typed so far.
     *
     * Every token is a **prefix** match. Without the trailing `*` FTS5 only matches whole tokens, so
     * a half-typed word finds nothing at all: "diab" returned zero conditions until the final "etes"
     * was typed, and "type 2 diab" likewise. A search box that is empty for seven of the eight
     * keystrokes it takes to reach a result reads as broken, and no amount of making it faster helps.
     *
     * One character is searched like any other. A single-letter prefix does cost more — about 10 ms
     * against the conditions table, almost all of it ranking the matches rather than finding them,
     * versus 0.3 ms from three letters on. That is off the main thread and behind a debounce, so it
     * is invisible; an empty list after typing a letter is not.
     *
     * Splitting on everything non-alphanumeric matches how the unicode61 tokenizer split the indexed
     * text; quoting stops `-`, `(` and `*` in the user's own input being read as operators, and one
     * token per quoted term keeps this a term query rather than a phrase, which a `detail='none'`
     * index cannot evaluate. Space-separated terms keep FTS5's implicit AND.
     */
    internal fun escapeFtsQuery(raw: String): String? {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "\"$it\"*" }
    }

    // --- Unpacking ---------------------------------------------------------

    /**
     * Expand the bundled asset if that hasn't happened yet. Cheap and idempotent once done, so any
     * screen that needs a picker can call it freely on every appearance.
     */
    // Broad catch inside is deliberate: the APK bytes are trusted but the disk is not —
    // any failure mode (I/O, storage, decode) lands in the same Failed status with the
    // previous database left in place, and cancellation still rethrows.
    @Suppress("TooGenericExceptionCaught")
    suspend fun prepare(): Result<Meta> = withContext(Dispatchers.IO) {
        // Cheap path, but only once the unpacked copy has been checked against the asset. Every
        // picker calls this on appearance, and re-reading two metadata files each time is pointless
        // after that.
        verified?.let { return@withContext Result.success(it) }

        prepareMutex.withLock {
            verified?.let { return@withLock Result.success(it) }

            val asset = assetMeta()
                ?: return@withLock fail("This build has no medical catalogue")

            if (asset.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return@withLock fail("Bundled medical catalogue is not readable by this build")
            }

            installedMeta()?.let { current ->
                if (current == asset) {
                    // Already unpacked from a previous run. Open it now rather than letting the
                    // first search pay for opening a 26 MB file.
                    openHandle()
                    verified = current
                    _status.value = Status.Ready(current)
                    return@withLock Result.success(current)
                }
            }

            val needed = asset.bytes * DB_SIZE_FACTOR
            val free = allocateForDatabase(needed)
            if (free in 1 until needed) {
                return@withLock fail("Not enough free space for the medical catalogue")
            }

            _status.value = Status.Preparing
            partDbFile.delete()

            try {
                decompressAsset()
                swapInDatabase(asset)
                Result.success(asset)
            } catch (e: Exception) {
                partDbFile.delete()
                Log.error(TAG, "Unpack failed", e)
                if (e is kotlinx.coroutines.CancellationException) {
                    _status.value = installedMeta()?.let { Status.Ready(it) } ?: Status.Absent
                    throw e
                }
                fail(e.message ?: "Couldn't prepare the medical catalogue")
            }
        }
    }

    /** Decompresses the bundled asset into the `.part` file. */
    private suspend fun decompressAsset() {
        BrotliInputStream(appContext.assets.open(ASSET_DB), BROTLI_BUFFER).use { input ->
            partDbFile.outputStream().buffered().use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var read = input.read(buffer)
                while (read >= 0) {
                    currentCoroutineContext().ensureActive()
                    if (read > 0) output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }
    }

    /** Swaps the `.part` file into place and opens it, recording the new metadata. */
    private fun swapInDatabase(asset: Meta) {
        closeHandle()
        dbFile.delete()
        if (!partDbFile.renameTo(dbFile)) {
            partDbFile.delete()
            throw IOException("Couldn't save the medical catalogue")
        }
        metaFile.writeText(json.encodeToString(asset))

        openHandle()
        verified = asset
        _status.value = Status.Ready(asset)
    }

    private fun assetMeta(): Meta? = try {
        appContext.assets.open(ASSET_META).bufferedReader().use {
            json.decodeFromString<Meta>(it.readText())
        }
    } catch (e: IOException) {
        Log.status(TAG, "No bundled medical catalogue asset: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.status(TAG, "No bundled medical catalogue asset: ${e.message}")
        null
    }

    private fun installedMeta(): Meta? {
        if (!dbFile.exists() || !metaFile.exists()) return null
        return try {
            val meta = json.decodeFromString<Meta>(metaFile.readText())
            if (meta.schemaVersion == SUPPORTED_SCHEMA_VERSION) meta else null
        } catch (e: IOException) {
            Log.error(TAG, "Unreadable unpacked metadata", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Unreadable unpacked metadata", e)
            null
        }
    }

    private fun openHandle(): SQLiteDatabase? {
        synchronized(lock) {
            handle?.let { return it }
            if (!dbFile.exists()) return null
            return try {
                // Empty passphrase => open as an ordinary unencrypted database. This is public
                // reference data, deliberately separate from the SQLCipher-encrypted Room database.
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    "",
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    null,
                ).also { handle = it }
            } catch (e: android.database.sqlite.SQLiteException) {
                Log.error(TAG, "Failed to open the medical catalogue", e)
                null
            }
        }
    }

    private fun closeHandle() {
        synchronized(lock) {
            try { handle?.close() } catch (_: Exception) {}
            handle = null
        }
    }

    /**
     * Free bytes we can actually use, reserving them up front. `File.usableSpace` ignores clearable
     * caches, so it under-reports and rejects installs the device could serve.
     */
    private fun allocateForDatabase(needed: Long): Long {
        val sm = appContext.getSystemService(StorageManager::class.java)
            ?: return appContext.filesDir.usableSpace
        return try {
            val uuid = sm.getUuidForPath(appContext.filesDir)
            val allocatable = sm.getAllocatableBytes(uuid)
            if (allocatable >= needed) sm.allocateBytes(uuid, needed)
            allocatable
        } catch (_: IOException) {
            appContext.filesDir.usableSpace
        }
    }

    private fun fail(message: String): Result<Meta> {
        _status.value = Status.Failed(message, installedMeta())
        return Result.failure(IllegalStateException(message))
    }
}
