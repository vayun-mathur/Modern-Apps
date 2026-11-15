package com.vayunmathur.health.util

import android.content.Context
import android.os.storage.StorageManager
import com.vayunmathur.health.data.NutritionData
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.loadSqlCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The nutrition database the recipe builder searches, shipped inside the APK.
 *
 * Ingredient search used to call `/api/food/search` and `/api/food/data/:id`
 * on every query and every tap. The whole database is now compiled into the
 * app as a gzipped asset by `scripts/generate_food_db.py`, so food lookup
 * makes no network requests at all - there is nothing to download and nothing
 * to configure. The server endpoints stay up only for previously shipped
 * builds; nothing here calls them.
 *
 * SQLite cannot query a file inside an APK, so the asset is expanded once into
 * `filesDir` on first use and reused from then on. That costs the unpacked
 * size in user storage on top of the compressed copy in the APK, which is why
 * the generator works hard to keep the database small.
 *
 * It is opened through SQLCipher's bundled SQLite rather than the platform's.
 * `libsqlcipher.so` is already in the APK via `:library:room`, so this costs
 * nothing, and it guarantees FTS5 is present at a known version - which is not
 * true of every OEM's system SQLite. The file itself is a plain, read-only,
 * unencrypted database: it is public reference data, kept deliberately
 * separate from the SQLCipher-encrypted Room database holding health records.
 *
 * Open Food Facts data, ODbL v1.0.
 */
object FoodDatabase {

    private const val TAG = "FoodDatabase"
    private const val ASSET_BIN = "food.bin.br"
    private const val ASSET_META = "food.bin.meta.json"
    private const val DB_FILE = "food.db"
    private const val PART_DB = "food.db.part"
    private const val PART_BIN = "food.bin.part"
    private const val META_FILE = "food.bin.meta.json"

    /** Leading bytes of the asset, "FDB1". */
    private val MAGIC = byteArrayOf(0x46, 0x44, 0x42, 0x31)

    /** Magic + version + row count + five section lengths. */
    private const val HEADER_BYTES = 29

    /** Header layout pieces: 4 magic bytes, 1 version byte, u32 row count, section lengths. */
    private const val VERSION_BYTES = 1

    /** Sections in the columnar asset: ids, scores, names, brands, nutrient blobs. */
    private const val SECTION_COUNT = 5

    /** Positions in the section-offset table. */
    private const val SECTION_IDS = 0
    private const val SECTION_SCORES = 1
    private const val SECTION_NAMES = 2
    private const val SECTION_BRANDS = 3
    private const val SECTION_BLOBS = 4

    /** Bytes in a little-endian u32 header field. */
    private const val U32_BYTES = 4

    /** Progress is reported every 8k rows. */
    private const val PROGRESS_MASK = 0x1FFFL

    /** Bind positions of the row-insert statement, in column order. */
    private const val BIND_ID = 1
    private const val BIND_NAME = 2
    private const val BIND_BRANDS = 3
    private const val BIND_NUTRIENTS = 4
    private const val BIND_SCORE = 5

    /** The unpacked database runs about three times the compressed asset; reserve that. */
    private const val DB_SIZE_FACTOR = 3

    /** Brotli decoder window and stream-copy buffer. */
    private const val BROTLI_BUFFER = 64 * 1024
    private const val COPY_BUFFER = 256 * 1024

    /**
     * Schema this build can read. Bumped in lockstep with
     * `generate_food_db.py`. Not merely advisory: [search] ranks with `bm25()`,
     * which is an error against a v2 index built without token positions, so
     * an older unpacked database has to be rejected and replaced rather than
     * used.
     */
    private const val SUPPORTED_SCHEMA_VERSION = 6

    /** Number of nutrients [NutritionData] carries, and the width of a blob's bitmap. */
    private const val NUTRIENT_COUNT = 41

    /** Leading bytes of a nutrient blob holding the presence bitmap. */
    private const val BITMAP_BYTES = 6

    /** Blob bit-packing widths; the format is documented on [decodeNutrients]. */
    private const val BITS_PER_BYTE = 8
    private const val BYTE_MASK = 0xFFL
    private const val BYTE_MASK_INT = 0xFF
    private const val VARINT_PAYLOAD_MASK = 0x7F
    private const val VARINT_CONTINUATION = 0x80
    private const val VARINT_SHIFT = 7
    private const val EXPONENT_BITS = 4
    private const val EXPONENT_MASK = 0xFL

    /** Decimal exponents a blob can encode; the exponent field is four bits. */
    private val POW10 = DoubleArray(16) { Math.pow(10.0, it.toDouble()) }

    /** Describes the bundled asset; emitted next to it by the generator. */
    @Serializable
    data class Meta(
        val schemaVersion: Int = 0,
        val rows: Long = 0,
        /** Unpacked size, used for extraction progress and free-space checks. */
        val bytes: Long = 0,
        val compressedBytes: Long = 0,
    )

    /** Contract for status. */
    sealed interface Status {
        /** The database that is unpacked and searchable right now, if any. */
        val installed: Meta? get() = null

        /** Not unpacked yet; ingredient search sees only locally saved ingredients. */
        data object Absent : Status

        /** First-run construction of the database, in products written. */
        data class Preparing(val productsWritten: Long, val totalProducts: Long) : Status

        /** Immutable value describing ready; carries meta. */
        data class Ready(val meta: Meta) : Status {
            override val installed: Meta get() = meta
        }

        /** Immutable value describing failed; carries message, installed. */
        data class Failed(val message: String, override val installed: Meta?) : Status
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context

    /** Serialises [prepare] so concurrent callers expand the asset only once. */
    private val prepareMutex = Mutex()

    /** Open handle to [dbFile], or null when nothing is unpacked. Guarded by [lock]. */
    private var handle: SQLiteDatabase? = null
    private val lock = Any()

    private val dbFile: File get() = File(appContext.filesDir, DB_FILE)
    private val partDbFile: File get() = File(appContext.filesDir, PART_DB)
    private val partBinFile: File get() = File(appContext.filesDir, PART_BIN)
    private val metaFile: File get() = File(appContext.filesDir, META_FILE)

    fun init(context: Context) {
        appContext = context.applicationContext
        loadSqlCipher()
        // Scratch files left behind by a killed build are never reusable.
        partDbFile.delete()
        partBinFile.delete()
        installedMeta()?.let { _status.value = Status.Ready(it) }
    }

    /** What the APK ships, or null if the asset is missing or unreadable. */
    private fun assetMeta(): Meta? = try {
        appContext.assets.open(ASSET_META).bufferedReader().use {
            json.decodeFromString<Meta>(it.readText())
        }
    } catch (e: IOException) {
        Log.error(TAG, "No bundled food database asset: ${e.message}", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.error(TAG, "No bundled food database asset: ${e.message}", e)
        null
    }

    /** What is already unpacked in `filesDir`, or null if nothing usable is. */
    private fun installedMeta(): Meta? {
        if (!dbFile.exists() || !metaFile.exists()) return null
        return try {
            val meta = json.decodeFromString<Meta>(metaFile.readText())
            if (meta.schemaVersion == SUPPORTED_SCHEMA_VERSION) meta else null
        } catch (e: IOException) {
            Log.error(TAG, "Unreadable unpacked metadata: ${e.message}", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.error(TAG, "Unreadable unpacked metadata: ${e.message}", e)
            null
        }
    }

    private fun openHandle(): SQLiteDatabase? {
        synchronized(lock) {
            handle?.let { return it }
            if (!dbFile.exists()) return null
            return try {
                // Empty passphrase => open as an ordinary unencrypted database.
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    "",
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                    null,
                ).also { handle = it }
            } catch (e: android.database.sqlite.SQLiteException) {
                Log.error(TAG, "Failed to open food database: ${e.message}", e)
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

    // --- Queries -----------------------------------------------------------

    /**
     * Turn raw user input into an FTS5 expression that cannot be a syntax
     * error, mirroring `escape_fts_query` in the server's `handlers/food.rs`.
     *
     * Input is split on everything that isn't alphanumeric, which is how the
     * unicode61 tokenizer splits the indexed text, so each quoted term is
     * exactly one token. The class is Unicode-aware, not ASCII: splitting
     * "café" or "jalapeño" on their accented letters would leave stumps that
     * match nothing. That matters twice over: quoting stops `-`, `(` and
     * `*` being read as operators, and keeping terms to a single token each
     * avoids emitting a phrase, which a `detail=none` index cannot evaluate.
     * Space-separated terms keep FTS5's implicit AND.
     *
     * Returns null when nothing searchable is left.
     */
    internal fun escapeFtsQuery(raw: String): String? {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "\"$it\"" }
    }

    /**
     * Ranked ingredient search over product names and brands.
     *
     * Ordering is the server's: products reporting at least one macronutrient
     * first (the rest are real but nearly useless in a recipe), then `bm25()`
     * relevance, then shortest name so "Milk" beats "Milk Chocolate Digestive
     * Biscuits".
     *
     * The one departure is bm25's column weights. A match here can be
     * satisfied by the brand as well as the name, and unweighted bm25 also
     * rewards sheer term repetition - "Sharp Cheddar Cheddar Cheese" outranks
     * plain "Cheddar Cheese" for `cheddar cheese`. Weighting the name five
     * times the brand settles both.
     *
     * bm25 is only trustworthy because the generator collapses duplicates
     * first; against the raw export it could not separate five identically
     * named "Whole milk" rows.
     */
    suspend fun search(query: String): List<FoodSearchAPI.SearchResult> = withContext(Dispatchers.IO) {
        val match = escapeFtsQuery(query) ?: return@withContext emptyList()
        val db = openHandle() ?: return@withContext emptyList()

        try {
            db.rawQuery(
                """
                SELECT p.id, p.product_name, p.brands
                FROM products_fts fts
                JOIN products p ON fts.rowid = p.id
                WHERE products_fts MATCH ?
                ORDER BY
                    (p.score >= 128) DESC,
                    bm25(products_fts, 10.0, 2.0) ASC,
                    LENGTH(p.product_name) ASC,
                    p.id ASC
                LIMIT 50
                """.trimIndent(),
                arrayOf(match),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val name = if (cursor.isNull(1)) "" else cursor.getString(1)
                        val brands = if (cursor.isNull(2)) "" else cursor.getString(2)
                        add(
                            FoodSearchAPI.SearchResult(
                                id = cursor.getLong(0),
                                displayName = if (brands.isEmpty()) name else "$name ($brands)",
                            )
                        )
                    }
                }
            }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Search Error: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Per-100g nutrition for one product, or null if it isn't in the database.
     *
     * Nutrients live in one sparse blob rather than 41 columns: only about
     * nine are ever reported, and a 41-column row costs 44 bytes of record
     * header plus 8 bytes per value whether or not the values exist. Anything
     * the blob omits was never reported and reads as 0.0, matching what
     * `/api/food/data/:id` returns.
     */
    suspend fun nutrition(id: Long): NutritionData? = withContext(Dispatchers.IO) {
        val db = openHandle() ?: return@withContext null

        try {
            db.rawQuery("SELECT nutrients FROM products WHERE id = ?", arrayOf(id.toString()))
                .use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    decodeNutrients(if (cursor.isNull(0)) ByteArray(0) else cursor.getBlob(0))
                }
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.error(TAG, "Fetch Data Error: ${e.message}", e)
            null
        }
    }

    /**
     * Expand a sparse nutrient blob written by `scripts/generate_food_db.py`.
     *
     *     bytes 0-5   little-endian bitmap, one bit per nutrient slot
     *     then        one LEB128 varint per set bit, ascending slot order:
     *                 (mantissa << 4) | exponent, value = mantissa * 10^-exponent
     *
     * The per-value decimal exponent is what lets one format carry both 900
     * kcal and 20 ug of vitamin D without either losing precision; a single
     * fixed scale rounds the small end to zero. Values are accurate to within
     * 0.5%, far finer than the two or three significant figures a nutrition
     * label carries.
     *
     * Reads defensively: a truncated blob, or a bitmap bit for a slot this
     * build doesn't know, yields zeros rather than throwing, so a database
     * written by a newer generator degrades instead of crashing the search.
     */
    internal fun decodeNutrients(blob: ByteArray): NutritionData {
        val v = DoubleArray(NUTRIENT_COUNT)
        if (blob.size >= BITMAP_BYTES) {
            val present = readPresenceBitmap(blob)
            var p = BITMAP_BYTES
            for (slot in 0 until NUTRIENT_COUNT) {
                // Unset slots cost no bytes; a truncated blob ends the value section early and
                // the remaining slots read as zero. p only grows, so skipping is equivalent to
                // stopping once past the end.
                if (p >= blob.size || (present shr slot) and 1L == 0L) continue
                val (value, next) = readVarint(blob, p)
                p = next
                v[slot] = decodeValue(value)
            }
        }

        return NutritionData(
            protein = v[0],
            carbohydrates = v[1],
            fat = v[2],
            fiber = v[3],
            sugar = v[4],
            sodium = v[5],
            biotin = v[6],
            caffeine = v[7],
            calcium = v[8],
            chloride = v[9],
            cholesterol = v[10],
            chromium = v[11],
            copper = v[12],
            folate = v[13],
            folicAcid = v[14],
            iodine = v[15],
            iron = v[16],
            magnesium = v[17],
            manganese = v[18],
            molybdenum = v[19],
            monounsaturatedFat = v[20],
            niacin = v[21],
            pantothenicAcid = v[22],
            phosphorus = v[23],
            polyunsaturatedFat = v[24],
            potassium = v[25],
            riboflavin = v[26],
            saturatedFat = v[27],
            selenium = v[28],
            thiamin = v[29],
            transFat = v[30],
            unsaturatedFat = v[31],
            vitaminA = v[32],
            vitaminB12 = v[33],
            vitaminB6 = v[34],
            vitaminC = v[35],
            vitaminD = v[36],
            vitaminE = v[37],
            vitaminK = v[38],
            zinc = v[39],
            calories = v[40],
        )
    }

    /** Little-endian presence bitmap from the blob's leading bytes. */
    private fun readPresenceBitmap(blob: ByteArray): Long {
        var present = 0L
        for (i in 0 until BITMAP_BYTES) {
            present = present or ((blob[i].toLong() and BYTE_MASK) shl (BITS_PER_BYTE * i))
        }
        return present
    }

    /** One LEB128 varint at [offset], returning the value and the offset past it. */
    private fun readVarint(blob: ByteArray, offset: Int): Pair<Long, Int> {
        var acc = 0L
        var shift = 0
        var p = offset
        while (p < blob.size) {
            val byte = blob[p++].toInt() and BYTE_MASK_INT
            acc = acc or ((byte and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (byte < VARINT_CONTINUATION) return acc to p
            shift += VARINT_SHIFT
        }
        return acc to p
    }

    /** `(mantissa << 4) | exponent` packed form to a double. */
    private fun decodeValue(packed: Long): Double =
        (packed shr EXPONENT_BITS).toDouble() / POW10[(packed and EXPONENT_MASK).toInt()]

    // --- Building ------------------------------------------------------------

    /**
     * Sequential reader over one section of the asset.
     *
     * The asset is columnar, so assembling a row needs one value from each of
     * five sections at once. Rather than hold 30 MB in memory, each section
     * gets its own stream positioned at its offset and they are advanced in
     * lockstep, which keeps the whole build to a few buffers.
     */
    private class Section(file: File, offset: Long) : java.io.Closeable {
        private val stream = java.io.FileInputStream(file).apply { channel.position(offset) }
            .buffered(64 * 1024)

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = stream.read()
                if (byte < 0) throw java.io.EOFException("truncated asset")
                result = result or ((byte and VARINT_PAYLOAD_MASK).toLong() shl shift)
                if (byte < VARINT_CONTINUATION) return result
                shift += VARINT_SHIFT
            }
        }

        fun readByte(): Int {
            val byte = stream.read()
            if (byte < 0) throw java.io.EOFException("truncated asset")
            return byte
        }

        fun readBlob(): ByteArray {
            val length = readVarint().toInt()
            val out = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = stream.read(out, read, length - read)
                if (n < 0) throw java.io.EOFException("truncated asset")
                read += n
            }
            return out
        }

        fun readString(): String = String(readBlob(), Charsets.UTF_8)

        override fun close() {
            try { stream.close() } catch (_: Exception) {}
        }
    }

    /**
     * Turn the decompressed asset into the SQLite database the app queries.
     *
     * Everything happens in one transaction with a single prepared statement,
     * and the FTS index is built at the end from the finished table rather
     * than incrementally, both of which matter at this row count.
     */
    private suspend fun buildDatabase(source: File, target: File, onRow: (Long) -> Unit) {
        val (rowCount, offsets) = readSectionOffsets(source)

        target.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(target.absolutePath, "", null, null)
        try {
            // journal_mode returns the resulting mode as a row; net.zetetic's
            // execSQL() runs everything as executeUpdateDelete and rejects any
            // result-producing statement ("Queries can be performed using ...
            // query or rawQuery methods only"). rawExecSQL is the correct call
            // for value-returning PRAGMAs.
            db.rawExecSQL("PRAGMA journal_mode = OFF")
            db.rawExecSQL("PRAGMA synchronous = OFF")
            db.rawExecSQL("PRAGMA temp_store = MEMORY")
            db.execSQL(
                """
                CREATE TABLE products (
                    id INTEGER PRIMARY KEY,
                    product_name TEXT,
                    brands TEXT,
                    nutrients BLOB,
                    score INTEGER
                )
                """.trimIndent()
            )

            insertRows(db, source, offsets, rowCount, onRow)
            onRow(rowCount)

            // detail=none: no token positions, which bm25() does not need -
            // see FoodDatabase.search. Built in one pass from the finished
            // table rather than row by row.
            db.execSQL(
                """
                CREATE VIRTUAL TABLE products_fts USING fts5(
                    product_name, brands,
                    content='products', content_rowid='id', detail='none'
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO products_fts(rowid, product_name, brands) " +
                    "SELECT id, product_name, brands FROM products"
            )
            // DELETE, not OFF: the database is reopened read-only, which a
            // journal mode of OFF does not survive cleanly.
            db.rawExecSQL("PRAGMA journal_mode = DELETE")
        } finally {
            try { db.close() } catch (_: Exception) {}
        }
    }

    /** Row count and the five section offsets from the asset header. */
    private fun readSectionOffsets(source: File): Pair<Long, LongArray> {
        val header = ByteArray(HEADER_BYTES)
        java.io.FileInputStream(source).use { input ->
            var read = 0
            while (read < HEADER_BYTES) {
                val n = input.read(header, read, HEADER_BYTES - read)
                if (n < 0) throw java.io.IOException("asset is shorter than its header")
                read += n
            }
        }
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "asset has the wrong magic"
        }
        require(header[MAGIC.size].toInt() == SUPPORTED_SCHEMA_VERSION) {
            "asset is format ${header[MAGIC.size].toInt()}, " +
                "this build reads $SUPPORTED_SCHEMA_VERSION"
        }

        fun u32(at: Int): Long {
            var value = 0L
            for (b in 0 until U32_BYTES) {
                value = value or (
                    (header[at + b].toLong() and BYTE_MASK) shl (BITS_PER_BYTE * b)
                    )
            }
            return value
        }

        val rowCount = u32(MAGIC.size + VERSION_BYTES)
        // Sections follow the header back to back, so each offset is the sum
        // of the lengths before it.
        var offset = HEADER_BYTES.toLong()
        val offsets = LongArray(SECTION_COUNT)
        for (i in 0 until SECTION_COUNT) {
            offsets[i] = offset
            offset += u32(MAGIC.size + VERSION_BYTES + U32_BYTES + U32_BYTES * i)
        }
        if (offset != source.length()) {
            throw java.io.IOException(
                "asset is ${source.length()} bytes, header describes $offset"
            )
        }
        return rowCount to offsets
    }

    /** Bulk-inserts every row in one transaction, reporting progress as it goes. */
    private suspend fun insertRows(
        db: SQLiteDatabase,
        source: File,
        offsets: LongArray,
        rowCount: Long,
        onRow: (Long) -> Unit,
    ) {
        val sections = mutableListOf<Section>()
        try {
            offsets.forEach { sections += Section(source, it) }
            insertSections(
                db,
                sections[SECTION_IDS],
                sections[SECTION_SCORES],
                sections[SECTION_NAMES],
                sections[SECTION_BRANDS],
                sections[SECTION_BLOBS],
                rowCount,
                onRow,
            )
        } finally {
            sections.forEach { section ->
                try {
                    section.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private suspend fun insertSections(
        db: SQLiteDatabase,
        ids: Section,
        scores: Section,
        names: Section,
        brands: Section,
        blobs: Section,
        rowCount: Long,
        onRow: (Long) -> Unit,
    ) {
        val insert = db.compileStatement(
            "INSERT INTO products (id, product_name, brands, nutrients, score) " +
                "VALUES (?, ?, ?, ?, ?)"
        )
        db.beginTransaction()
        try {
            var id = 0L
            for (row in 0 until rowCount) {
                // Ids ascend, so the asset stores them as deltas.
                id += ids.readVarint()
                insert.clearBindings()
                insert.bindLong(BIND_ID, id)
                insert.bindString(BIND_NAME, names.readString())
                insert.bindString(BIND_BRANDS, brands.readString())
                insert.bindBlob(BIND_NUTRIENTS, blobs.readBlob())
                insert.bindLong(BIND_SCORE, scores.readByte().toLong())
                insert.executeInsert()

                if (row and PROGRESS_MASK == 0L) {
                    currentCoroutineContext().ensureActive()
                    onRow(row)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Build the database from the bundled asset if that hasn't happened yet.
     *
     * Cheap and idempotent once built, so callers can invoke it freely
     * whenever a screen that needs food search appears. Re-runs by itself
     * after an app update that ships different data.
     *
     * Works into `.part` files and swaps in only on success, so a failure or a
     * process death midway can never leave a half-built database in place. No
     * checksum is needed: the bytes come from the APK, which the platform has
     * already verified.
     */
    // Broad catch inside is deliberate: the APK bytes are trusted but the disk is not —
    // any failure mode (I/O, storage, decode) lands in the same Failed status with the
    // previous database left in place, and cancellation still rethrows.
    @Suppress("TooGenericExceptionCaught")
    suspend fun prepare(): Result<Meta> = withContext(Dispatchers.IO) {
        prepareMutex.withLock {
            val asset = assetMeta()
                ?: return@withLock fail("This build has no food database")

            if (asset.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return@withLock fail("Bundled food database is not readable by this build")
            }

            installedMeta()?.let { current ->
                if (current == asset) {
                    _status.value = Status.Ready(current)
                    return@withLock Result.success(current)
                }
            }

            // The decompressed asset and the database it builds are both on
            // disk at once, and the database runs appreciably larger.
            val needed = asset.bytes * DB_SIZE_FACTOR
            val free = allocateForDatabase(needed)
            if (free in 1 until needed) {
                return@withLock fail("Not enough free space for the food database")
            }

            _status.value = Status.Preparing(0, asset.rows)
            partBinFile.delete()
            partDbFile.delete()

            try {
                decompressAsset()
                buildDatabase(partBinFile, partDbFile) { row ->
                    _status.value = Status.Preparing(row, asset.rows)
                }
                partBinFile.delete()
                swapInDatabase(asset)
                Result.success(asset)
            } catch (e: Exception) {
                partBinFile.delete()
                partDbFile.delete()
                Log.error(TAG, "Build Error: ${e.message}", e)
                if (e is kotlinx.coroutines.CancellationException) {
                    _status.value = installedMeta()?.let { Status.Ready(it) } ?: Status.Absent
                    throw e
                }
                fail(e.message ?: "Couldn't prepare the food database")
            }
        }
    }

    /** Decompresses the bundled asset into the `.part` file. */
    private suspend fun decompressAsset() {
        BrotliInputStream(appContext.assets.open(ASSET_BIN), BROTLI_BUFFER).use { input ->
            partBinFile.outputStream().buffered().use { output ->
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

    /** Swaps the `.part` database into place and records the new metadata. */
    private fun swapInDatabase(asset: Meta) {
        closeHandle()
        dbFile.delete()
        if (!partDbFile.renameTo(dbFile)) {
            partDbFile.delete()
            throw IOException("Couldn't save the food database")
        }
        metaFile.writeText(json.encodeToString(asset))

        _status.value = Status.Ready(asset)
    }

    /**
     * Free bytes we can actually use, reserving them up front. `File.usableSpace` ignores
     * clearable caches, so it under-reports and rejects installs the device could serve.
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
        // A failed rebuild leaves the previous database in place and usable.
        _status.value = Status.Failed(message, installedMeta())
        return Result.failure(IllegalStateException(message))
    }
}
