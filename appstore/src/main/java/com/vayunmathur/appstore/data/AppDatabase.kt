package com.vayunmathur.appstore.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

const val DB_NAME = "appstore-db"

@Entity
data class RepoEntity(
    @PrimaryKey val url: String,
    val name: String,
    val enabled: Boolean = true,
    /** SHA-256 of the certificate the repo's index JAR must be signed with. */
    val fingerprint: String? = null,
    val lastSync: Long = 0L
)

@Entity
data class CachedAppEntity(
    @PrimaryKey val packageName: String,
    val source: String, // MODERN_APPS, FDROID, PLAYSTORE
    val name: String,
    val summary: String,
    val description: String,
    val iconUrl: String?,
    val author: String?,
    val categories: String, // comma joined
    val versionName: String?,
    val versionCode: Long,
    val sizeBytes: Long,
    val apkUrl: String?,
    val targetSdk: Int?,
    /** True when this version was reproduced bit-for-bit by F-Droid's verification server. */
    val reproducible: Boolean = false,
    val repoUrl: String?,
    val lastUpdated: Long,
    /** Comma-joined SHA-256 signing-certificate fingerprints from an authenticated index. */
    val expectedSigners: String? = null,
    /** SHA-256 of the APK itself, from an authenticated index. */
    val apkSha256: String? = null,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null,
    /** Newline-joined listing screenshot URLs, in publication order. */
    val screenshots: String? = null,
    val featureGraphic: String? = null,
    val rating: Float? = null,
    val ratingCount: Long = 0L,
    val installs: Long = 0L,
    val updatedOn: String? = null,
    val contentRating: String? = null,
    val containsAds: Boolean = false,
    /** Comma-joined F-Droid anti-feature identifiers. */
    val antiFeatures: String? = null,
    val whatsNew: String? = null,
    val addedTimestamp: Long = 0L,
    /** Newline-joined TV screenshot URLs, in publication order. Null when none published. */
    val tvScreenshots: String? = null,
    /** Comma-joined `<uses-feature>` names from an authenticated index. Null when none published. */
    val requiredFeatures: String? = null,
    /** TV-compat verdict from the catalogue; null when the source said nothing either way. */
    val tvCompatible: Boolean? = null,
    /** Split-set siblings, one per line as `fileName\turl\tsha256\tsize`. Null for monolithic APKs. */
    val splitFiles: String? = null,
)

/** URLs never contain a newline, so this survives values a comma would split apart. */
private const val LIST_SEP = "\n"

private fun List<String>.packList(): String? =
    filter { it.isNotBlank() }.joinToString(LIST_SEP).ifBlank { null }

private fun String?.unpackList(): List<String> =
    this?.split(LIST_SEP)?.filter { it.isNotBlank() } ?: emptyList()

private const val FIELD_SEP = "\t"
private const val SPLIT_FIELDS = 4

internal fun List<SplitFile>.packSplits(): String? =
    map { listOf(it.fileName, it.url, it.sha256.orEmpty(), it.size.toString()).joinToString(FIELD_SEP) }
        .packList()

internal fun String?.unpackSplits(): List<SplitFile> = unpackList().mapNotNull { line ->
    val fields = line.split(FIELD_SEP)
    if (fields.size != SPLIT_FIELDS) return@mapNotNull null
    SplitFile(
        fileName = fields[0],
        url = fields[1],
        sha256 = fields[2].ifBlank { null },
        size = fields[3].toLongOrNull() ?: 0L,
    )
}

fun UnifiedApp.toEntity(): CachedAppEntity = CachedAppEntity(
    packageName = packageName,
    source = source.name,
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    author = author,
    categories = categories.joinToString(","),
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    apkUrl = apkUrl,
    targetSdk = targetSdk,
    reproducible = reproducible,
    repoUrl = repoUrl?.removeSuffix("/") ?: DefaultRepos.FDROID.url,
    lastUpdated = lastUpdated,
    expectedSigners = expectedSigners.joinToString(",").ifBlank { null },
    apkSha256 = apkSha256,
    license = license,
    website = website,
    sourceCode = sourceCode,
    screenshots = screenshots.packList(),
    featureGraphic = featureGraphic,
    rating = rating,
    ratingCount = ratingCount,
    installs = installs,
    updatedOn = updatedOn,
    contentRating = contentRating,
    containsAds = containsAds,
    antiFeatures = antiFeatures.packList(),
    whatsNew = whatsNew,
    addedTimestamp = addedTimestamp,
    tvScreenshots = tvScreenshots.packList(),
    requiredFeatures = requiredFeatures.joinToString(",").ifBlank { null },
    tvCompatible = tvCompatible,
    splitFiles = splitFiles.packSplits(),
)

fun CachedAppEntity.toUnifiedApp(): UnifiedApp = UnifiedApp(
    packageName = packageName,
    source = try { AppSource.valueOf(source) } catch (_: Exception) { AppSource.FDROID },
    name = name,
    summary = summary,
    description = description,
    iconUrl = iconUrl,
    author = author,
    categories = categories.split(",").filter { it.isNotBlank() },
    versionName = versionName,
    versionCode = versionCode,
    sizeBytes = sizeBytes,
    apkUrl = apkUrl,
    targetSdk = targetSdk,
    reproducible = reproducible,
    repoUrl = repoUrl,
    lastUpdated = lastUpdated,
    expectedSigners = expectedSigners?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    apkSha256 = apkSha256,
    license = license,
    website = website,
    sourceCode = sourceCode,
    screenshots = screenshots.unpackList(),
    featureGraphic = featureGraphic,
    rating = rating,
    ratingCount = ratingCount,
    installs = installs,
    updatedOn = updatedOn,
    contentRating = contentRating,
    containsAds = containsAds,
    antiFeatures = antiFeatures.unpackList(),
    whatsNew = whatsNew,
    addedTimestamp = addedTimestamp,
    tvScreenshots = tvScreenshots.unpackList(),
    requiredFeatures = requiredFeatures?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    tvCompatible = tvCompatible,
    splitFiles = splitFiles.unpackSplits(),
)

/**
 * Trust-on-first-use pin of a package's APK source-stamp certificate.
 *
 * Only meaningful for Play, where the APK signing key belongs to Google and so cannot
 * identify the publisher. The stamp survives Play's re-signing, so pinning it detects a
 * change of publisher identity across updates. See
 * [com.vayunmathur.appstore.data.security.SourceStamp] for what this does and does not
 * prove.
 */
@Entity
data class PinnedStampEntity(
    @PrimaryKey val packageName: String,
    val stampSha256: String,
    val firstSeen: Long,
)

@Dao
interface PinnedStampDao {
    @Query("SELECT * FROM PinnedStampEntity WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): PinnedStampEntity?

    @Upsert
    suspend fun upsert(pin: PinnedStampEntity)

    @Query("DELETE FROM PinnedStampEntity WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)
}

/**
 * One entry of Accrescent's ed25519-signed allowlist, cached in Room.
 *
 * This is the trust anchor for an Accrescent install: [signingCertHash] is the certificate the
 * app's APK must be signed with (lowercase-hex SHA-256 of the cert DER, the same encoding the
 * rest of the store uses), and [minVersionCode] is the oldest version that may be installed
 * (rollback protection). Populated only from a verified [com.vayunmathur.appstore.data.accrescent.RepoData];
 * see [com.vayunmathur.appstore.data.accrescent.AccrescentTrustStore].
 */
@Entity
data class AccrescentTrustEntity(
    @PrimaryKey val appId: String,
    val signingCertHash: String,
    val minVersionCode: Long,
)

@Dao
interface AccrescentTrustDao {
    @Query("SELECT * FROM AccrescentTrustEntity WHERE appId = :appId LIMIT 1")
    suspend fun byId(appId: String): AccrescentTrustEntity?

    @Query("SELECT appId FROM AccrescentTrustEntity")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM AccrescentTrustEntity")
    suspend fun clearAll()

    @Upsert
    suspend fun upsertAll(entries: List<AccrescentTrustEntity>)

    /**
     * Replace the whole allowlist atomically. The signed repodata is authoritative in full —
     * an app id it no longer lists must stop being trusted — so this clears first, in one
     * transaction, rather than merging.
     */
    @androidx.room3.Transaction
    suspend fun replaceAll(entries: List<AccrescentTrustEntity>) {
        clearAll()
        upsertAll(entries)
    }
}

@Dao
interface RepoDao {
    @Query("SELECT * FROM RepoEntity ORDER BY name ASC")
    fun allFlow(): Flow<List<RepoEntity>>

    @Query("SELECT * FROM RepoEntity")
    suspend fun all(): List<RepoEntity>

    @Upsert
    suspend fun upsert(repo: RepoEntity)

    @Delete
    suspend fun delete(repo: RepoEntity)

    @Query("DELETE FROM RepoEntity WHERE url = :url")
    suspend fun deleteByUrl(url: String)
}

/**
 * The three columns every catalogue-wide question needs, without dragging each app's
 * description and screenshot list into memory with them.
 *
 * The store caches the whole F-Droid index — several thousand rows — so the old habit of
 * collecting `allFlow()` and filtering in Kotlin meant holding the entire catalogue live
 * just to answer "is this package known, and is it newer than what's installed".
 */
data class PackageIndexRow(
    val packageName: String,
    val source: String,
    val versionCode: Long,
)

@Dao
interface CachedAppDao {
    @Query("SELECT * FROM CachedAppEntity ORDER BY name ASC")
    fun allFlow(): Flow<List<CachedAppEntity>>

    /** Package → source/version, for attribution and update detection. */
    @Query("SELECT packageName, source, versionCode FROM CachedAppEntity")
    fun indexFlow(): Flow<List<PackageIndexRow>>

    /**
     * Same rows as [indexFlow] as a one-shot read.
     *
     * The flow's [StateFlow] cache starts as an empty map until Room emits, so a cold-start
     * update check reading `.value` sees no packages and asks Play about every installed
     * app — including ones the offline catalogue lists. Those phantom Play rows then
     * vanish on the next refresh once the flow has populated. A direct query always
     * reflects what is actually cached.
     */
    @Query("SELECT packageName, source, versionCode FROM CachedAppEntity")
    suspend fun indexList(): List<PackageIndexRow>

    @Query("SELECT * FROM CachedAppEntity WHERE source = :source ORDER BY name ASC")
    fun bySourceFlow(source: String): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM CachedAppEntity WHERE source = :source ORDER BY name ASC")
    suspend fun bySourceAll(source: String): List<CachedAppEntity>

    @Query(
        "SELECT * FROM CachedAppEntity WHERE ',' || categories || ',' LIKE '%,' || :category || ',%' " +
            "ORDER BY name ASC"
    )
    suspend fun byCategoryAll(category: String): List<CachedAppEntity>

    /**
     * Newest builds from one offline source only. Each repo gets its own home
     * row, so the catalogue is never asked for a cross-source "recent" mix.
     */
    @Query("SELECT * FROM CachedAppEntity WHERE source = :source ORDER BY lastUpdated DESC LIMIT :limit")
    suspend fun recentlyUpdatedBySource(source: String, limit: Int): List<CachedAppEntity>

    @Query(
        "SELECT * FROM CachedAppEntity WHERE ',' || categories || ',' LIKE '%,' || :category || ',%' " +
            "ORDER BY lastUpdated DESC LIMIT :limit"
    )
    suspend fun byCategory(category: String, limit: Int): List<CachedAppEntity>

    @Query("SELECT categories FROM CachedAppEntity WHERE categories != ''")
    suspend fun allCategoryStrings(): List<String>

    @Query("SELECT * FROM CachedAppEntity WHERE packageName IN (:packages)")
    suspend fun byPackages(packages: List<String>): List<CachedAppEntity>

    @Query(
        "SELECT * FROM CachedAppEntity WHERE " +
            "packageName LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' " +
            "OR summary LIKE '%' || :q || '%' ORDER BY name ASC LIMIT :limit"
    )
    suspend fun searchAll(q: String, limit: Int): List<CachedAppEntity>

    @Query(
        "SELECT * FROM CachedAppEntity WHERE packageName LIKE '%' || :q || '%' " +
            "OR name LIKE '%' || :q || '%' ORDER BY name ASC"
    )
    fun searchFlow(q: String): Flow<List<CachedAppEntity>>

    @Query("SELECT * FROM CachedAppEntity")
    suspend fun all(): List<CachedAppEntity>

    @Query("SELECT * FROM CachedAppEntity WHERE packageName = :pkg LIMIT 1")
    suspend fun byPackage(pkg: String): CachedAppEntity?

    @Query(
        "SELECT * FROM CachedAppEntity WHERE source = :source AND (" +
            "packageName LIKE '%' || :q || '%' OR name LIKE '%' || :q || '%' " +
            "OR summary LIKE '%' || :q || '%') ORDER BY name ASC LIMIT 40"
    )
    suspend fun search(source: String, q: String): List<CachedAppEntity>

    @Upsert
    suspend fun upsertAll(apps: List<CachedAppEntity>)

    @Upsert
    suspend fun upsert(app: CachedAppEntity)

    /**
     * Replace one repo's rows atomically. Delete-then-upsert without a transaction
     * leaves a window where the index is empty and a concurrent update check sees no
     * packages — asking Play about every installed app, including ones this repo
     * lists. Those phantom Play rows then vanish on the next refresh.
     */
    @androidx.room3.Transaction
    suspend fun replaceByRepo(repoUrl: String, apps: List<CachedAppEntity>) {
        deleteByRepo(repoUrl)
        upsertAll(apps)
    }

    @Query("DELETE FROM CachedAppEntity WHERE repoUrl = :repoUrl")
    suspend fun deleteByRepo(repoUrl: String)

    @Query("DELETE FROM CachedAppEntity")
    suspend fun clearAll()
}

@Database(
    entities = [
        RepoEntity::class,
        CachedAppEntity::class,
        PinnedStampEntity::class,
        AccrescentTrustEntity::class,
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun repoDao(): RepoDao
    abstract fun cachedAppDao(): CachedAppDao
    abstract fun pinnedStampDao(): PinnedStampDao
    abstract fun accrescentTrustDao(): AccrescentTrustDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations = listOf(
            object : Migration(1, 2) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("DROP TABLE IF EXISTS FavoriteEntity")
                }
            },
            object : Migration(2, 3) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN targetSdk INTEGER")
                }
            },
            object : Migration(3, 4) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN expectedSigners TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN apkSha256 TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN license TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN website TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN sourceCode TEXT")
                    // Rows cached before this version carry no signer or hash, and the
                    // reproducible-only filter had not run yet. Drop them so nothing
                    // unverifiable survives the upgrade; the next sync repopulates.
                    connection.execSQL("DELETE FROM CachedAppEntity")
                    connection.execSQL("DELETE FROM RepoEntity")
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS PinnedStampEntity (" +
                            "packageName TEXT NOT NULL PRIMARY KEY, " +
                            "stampSha256 TEXT NOT NULL, " +
                            "firstSeen INTEGER NOT NULL)"
                    )
                }
            },
            object : Migration(4, 5) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Listing media and store metadata. All nullable or defaulted, so
                    // existing rows stay usable and fill in on the next sync.
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN screenshots TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN featureGraphic TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN rating REAL")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN ratingCount INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN installs INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN updatedOn TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN contentRating TEXT")
                    connection.execSQL(
                        "ALTER TABLE CachedAppEntity ADD COLUMN containsAds INTEGER NOT NULL DEFAULT 0"
                    )
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN antiFeatures TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN whatsNew TEXT")
                    connection.execSQL(
                        "ALTER TABLE CachedAppEntity ADD COLUMN addedTimestamp INTEGER NOT NULL DEFAULT 0"
                    )
                }
            },
            object : Migration(5, 6) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Reproducibility became a per-app badge rather than an import gate, so
                    // the catalogue now lists non-reproduced F-Droid apps too. Existing rows
                    // predate the badge; the next sync repopulates it. Default false.
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN reproducible INTEGER NOT NULL DEFAULT 0")
                }
            },
            object : Migration(6, 7) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Accrescent's signed allowlist, cached as a trust anchor. Empty until the
                    // first repodata fetch verifies and populates it; nothing installs from
                    // Accrescent until then, which is the intended fail-closed default.
                    connection.execSQL(
                        "CREATE TABLE IF NOT EXISTS AccrescentTrustEntity (" +
                            "appId TEXT NOT NULL PRIMARY KEY, " +
                            "signingCertHash TEXT NOT NULL, " +
                            "minVersionCode INTEGER NOT NULL)"
                    )
                }
            },
            object : Migration(7, 8) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // TV-compat catalogue signals: the per-version `<uses-feature>` list, the
                    // TV screenshot set, and the derived verdict. All nullable, so existing
                    // rows stay usable and fill in on the next sync; a null verdict means
                    // "unknown", which the filter treats as compatible.
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN tvScreenshots TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN requiredFeatures TEXT")
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN tvCompatible INTEGER")
                }
            },
            object : Migration(8, 9) {
                override suspend fun migrate(connection: SQLiteConnection) {
                    // Rows cached before this lost their split siblings, so a split-set
                    // install committed only the base and failed with MISSING_SPLIT.
                    // Drop them so no split-less row survives; the next sync repopulates.
                    connection.execSQL("ALTER TABLE CachedAppEntity ADD COLUMN splitFiles TEXT")
                    connection.execSQL("DELETE FROM CachedAppEntity")
                }
            }
        )
    }
}
