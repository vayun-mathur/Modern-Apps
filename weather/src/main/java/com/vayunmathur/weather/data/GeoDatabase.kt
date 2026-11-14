package com.vayunmathur.weather.data

import android.content.Context
import android.os.storage.StorageManager
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.room.loadSqlCipher
import com.vayunmathur.weather.network.GeocodingResult
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
 * The place names the city search suggests, shipped inside the APK.
 *
 * One table in one SQLite file, built by `scripts/generate_places_db.py`
 * from GeoNames `cities1000` (+ `admin1CodesASCII`, `countryInfo`): ~171k
 * places with canonical name, ASCII name, hidden alternate names, coords,
 * country/admin1 display names, population, and time zone.
 *
 * No search makes a network request; the downloads happen at build time.
 * GeoNames is CC-BY 4.0 (https://www.geonames.org/about.html); attribution
 * ships in the app's about screen.
 *
 * Deliberately mirrors the health app's `ReferenceCatalog`: expanded into
 * `filesDir` once, opened read-only through SQLCipher's bundled SQLite
 * (which guarantees a known FTS5) with an empty passphrase, and written
 * through a `.part` file so a failure midway cannot leave a half-built
 * database in place.
 *
 * The ranking SQL is kept byte-for-byte in step with the generator's
 * documented ORDER BY (exact, then prefix, then population, then bm25) so a
 * rebuild never silently reshuffles results.
 *
 * When the asset is absent — e.g. a corrupt install — [status] stays
 * [Status.Absent] and search returns no results rather than failing.
 */
object GeoDatabase {
    const val COL_ID = 0
    const val COL_NAME = 1
    const val COL_LATITUDE = 2
    const val COL_LONGITUDE = 3
    const val COL_COUNTRY = 4
    const val COL_COUNTRY_CODE = 5
    const val COL_ADMIN1 = 6
    const val COL_TIMEZONE = 7

    private const val TAG = "GeoDatabase"
    private const val ASSET_DB = "places.db.br"
    private const val ASSET_META = "places.db.meta.json"
    private const val DB_FILE = "places.db"
    private const val PART_DB = "places.db.part"
    private const val META_FILE = "places.db.meta.json"

    /** Schema this build can read. Bumped in lockstep with `generate_places_db.py`. */
    private const val SUPPORTED_SCHEMA_VERSION = 1

    /** The unpacked database runs about three times the asset size; reserve that up front. */
    private const val DB_SIZE_FACTOR = 3

    /** Brotli decoder window and stream-copy buffer. */
    private const val BROTLI_BUFFER = 64 * 1024
    private const val COPY_BUFFER = 256 * 1024

    /** Describes the bundled asset; emitted next to it by the generator. */
    @Serializable
    data class Meta(
        val schemaVersion: Int = 0,
        val places: Long = 0,
        /** Unpacked size, used for the free-space check. */
        val bytes: Long = 0,
        val compressedBytes: Long = 0,
    )

    /** Contract for status. */
    sealed interface Status {
        val installed: Meta? get() = null

        /** Not unpacked yet; search returns no results. */
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

    private val json = Json { ignoreUnknownKeys = true }

    private val _status = MutableStateFlow<Status>(Status.Absent)
    val status: StateFlow<Status> = _status.asStateFlow()

    private lateinit var appContext: Context

    private val prepareMutex = Mutex()

    /**
     * The unpacked database, once confirmed to match the asset in this process.
     * Compares the whole descriptor rather than just [Meta.schemaVersion]:
     * two builds can share a schema and hold different data.
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
    }

    /**
     * Unpack and open in the background, so the first search the user types
     * does not have to. Unpacking is a ~13 MB brotli decompression and disk
     * write — about a second, once.
     */
    fun warmUp(scope: CoroutineScope) {
        scope.launch { runCatching { prepare() } }
    }

    /**
     * Places matching [query], best first.
     *
     * Ranking (same ORDER BY as the generator documents): exact canonical or
     * ASCII name, then prefix matches, then population (famous cities win
     * ties), then bm25. Empty when the catalogue is absent or when nothing
     * matches.
     */
    suspend fun searchPlaces(query: String, limit: Int = 5): List<GeocodingResult> =
        withContext(Dispatchers.IO) {
            val match = escapeFtsQuery(query) ?: return@withContext emptyList()
            val db = openHandle() ?: return@withContext emptyList()
            val trimmed = query.trim()
            // LIKE prefix pattern. Escape char is `!` (not backslash: in a
            // Kotlin raw string '\\' reaches SQLite as two characters, which
            // it rejects — "ESCAPE expression must be a single character").
            val like = trimmed
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_") + "%"
            try {
                db.rawQuery(
                    """
                    SELECT p.id, p.name, p.latitude, p.longitude,
                        p.country_name, p.country_code, p.admin1, p.timezone
                    FROM places_fts fts
                    JOIN places p ON fts.rowid = p.rowid
                    WHERE places_fts MATCH ?
                    ORDER BY
                        (p.name = ? OR p.asciiname = ?) DESC,
                        (p.name LIKE ? ESCAPE '!' OR p.asciiname LIKE ? ESCAPE '!') DESC,
                        p.population DESC,
                        bm25(places_fts)
                    LIMIT ?
                    """.trimIndent(),
                    arrayOf(match, trimmed, trimmed, like, like, limit.toString()),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                GeocodingResult(
                                    id = cursor.getLong(COL_ID),
                                    name = cursor.getString(COL_NAME),
                                    latitude = cursor.getDouble(COL_LATITUDE),
                                    longitude = cursor.getDouble(COL_LONGITUDE),
                                    country = cursor.getString(COL_COUNTRY)?.takeIf { it.isNotEmpty() },
                                    countryCode = cursor.getString(COL_COUNTRY_CODE)?.takeIf { it.isNotEmpty() },
                                    admin1 = cursor.getString(COL_ADMIN1)?.takeIf { it.isNotEmpty() },
                                    timezone = cursor.getString(COL_TIMEZONE)?.takeIf { it.isNotEmpty() },
                                ),
                            )
                        }
                    }
                }
            } catch (e: android.database.sqlite.SQLiteException) {
                Log.error(TAG, "Place search failed", e)
                emptyList()
            }
        }

    /**
     * Turn raw user input into an FTS5 expression.
     *
     * Every token is a **prefix** match (`"ber"*`). Without the trailing `*`
     * FTS5 only matches whole tokens, so a half-typed word finds nothing: a
     * search box that is empty until the last keystroke reads as broken.
     * Splitting on everything non-alphanumeric matches how the unicode61
     * tokenizer split the indexed text; quoting stops `-`, `(` and `*` in
     * the user's own input being read as operators.
     */
    internal fun escapeFtsQuery(raw: String): String? {
        val tokens = raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return if (tokens.isEmpty()) null else tokens.joinToString(" ") { "\"$it\"*" }
    }

    // --- Unpacking (mirrors ReferenceCatalog) --------------------------------

    /**
     * Expand the bundled asset if that hasn't happened yet. Cheap and
     * idempotent once done, so the search screen can call it freely on every
     * appearance.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun prepare(): Result<Meta> = withContext(Dispatchers.IO) {
        verified?.let { return@withContext Result.success(it) }

        prepareMutex.withLock {
            verified?.let { return@withLock Result.success(it) }

            val asset = assetMeta()
                ?: return@withLock fail("This build has no places catalogue")

            if (asset.schemaVersion != SUPPORTED_SCHEMA_VERSION) {
                return@withLock fail("Bundled places catalogue is not readable by this build")
            }

            installedMeta()?.let { current ->
                if (current == asset) {
                    openHandle()
                    verified = current
                    _status.value = Status.Ready(current)
                    return@withLock Result.success(current)
                }
            }

            val needed = asset.bytes * DB_SIZE_FACTOR
            val free = allocateForDatabase(needed)
            if (free in 1 until needed) {
                return@withLock fail("Not enough free space for the places catalogue")
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
                fail(e.message ?: "Couldn't prepare the places catalogue")
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
            throw IOException("Couldn't save the places catalogue")
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
        Log.status(TAG, "No bundled places catalogue asset: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        Log.status(TAG, "No bundled places catalogue asset: ${e.message}")
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
                Log.error(TAG, "Failed to open the places catalogue", e)
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
