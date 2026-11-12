package com.vayunmathur.photos.data

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Delete
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.Upsert
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    // Excludes the heavy clipEmbedding BLOB, ocrText and ocrBoxes, which the
    // gallery/photo UI never reads, so the first emission on cold start is fast
    // (loading those for a large library is what left the grid blank). Semantic
    // search reads embeddings via getClipEmbeddings(); OCR text is read via
    // getAll(); the viewer's selectable-text overlay reads geometry by id via
    // getOcrBoxes(), because Photos from this flow always have ocrBoxes == null.
    //
    // ORDER BY date DESC is served by index_Photo_date, so SQLite returns rows
    // newest-first on its own background executor. Every consumer (gallery grid,
    // trash, people) groups/sorts by date descending anyway, so the composition
    // pass now groups pre-ordered rows instead of sorting the whole library on
    // the main thread — cutting first-load jank without changing what's shown.
    @Query(
        "SELECT id, name, uri, date, width, height, dateModified, exifSet, lat, `long`, duration, " +
            "fullWidth, fullHeight, croppedWidth, croppedHeight, croppedLeft, croppedTop, projectionType, " +
            "isTrashed, faceScanned, ocrScanned, clipScanned, mimeType, album, isFavorite FROM Photo ORDER BY date DESC"
    )
    fun getAllFlow(): Flow<List<Photo>>

    @Query("SELECT * FROM Photo WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Photo?>

    @Query("SELECT * FROM Photo")
    suspend fun getAll(): List<Photo>

    @Query("SELECT * FROM Photo WHERE uri = :uri")
    suspend fun getByUri(uri: String): List<Photo>

    @Upsert
    suspend fun upsertAll(photos: List<Photo>)

    @Delete
    suspend fun delete(value: Photo): Int

    @Query("DELETE FROM Photo WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE Photo SET isTrashed = 1 WHERE id = :id")
    suspend fun setTrashed(id: Long)

    /**
     * Retarget the MediaStore bucket (album) column for [ids]. Column-targeted so
     * it never rewrites the heavy clipEmbedding BLOB — the Albums move optimistically
     * reflects a folder change before the next sync backfills BUCKET_DISPLAY_NAME.
     */
    @Query("UPDATE Photo SET album = :album WHERE id IN (:ids)")
    suspend fun setAlbum(ids: List<Long>, album: String?)

    /**
     * Mirror MediaStore's IS_FAVORITE for [ids]. Column-targeted, like [setAlbum],
     * so neither the optimistic toggle nor the sync's favourite-only diff
     * rewrites (and nulls) the clipEmbedding BLOB.
     */
    @Query("UPDATE Photo SET isFavorite = :value WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Long>, value: Boolean)

    @Query(
        "SELECT * FROM Photo WHERE isTrashed = 0 AND " +
            "(ocrText LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%') ORDER BY date DESC"
    )
    suspend fun searchPhotos(query: String): List<Photo>

    /** Photos already scanned by OCR (numerator of the search-index progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE ocrScanned = 1 AND isTrashed = 0 AND duration IS NULL")
    fun getOCRCountFlow(): Flow<Int>

    /**
     * Photos that count toward on-device indexing — the denominator shared by the
     * OCR, CLIP and face progress bars.
     *
     * One query rather than three: the OCR/CLIP/face variants were byte-identical,
     * so every write during a scan re-ran the same full table scan three times for
     * three observers of the same number.
     */
    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getIndexTargetCountFlow(): Flow<Int>

    /** Photos with a stored embedding, i.e. actually indexed (progress numerator). */
    @Query("SELECT count(*) FROM Photo WHERE clipEmbedding IS NOT NULL AND isTrashed = 0 AND duration IS NULL")
    fun getClipCountFlow(): Flow<Int>

    /** Photos already scanned for faces (numerator of the progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE faceScanned = 1 AND isTrashed = 0 AND duration IS NULL")
    fun getFaceScannedCountFlow(): Flow<Int>
}

/**
 * Indexing reads/writes (OCR, CLIP, faces, EXIF backfill), split out of
 * [PhotoDao] so neither interface exceeds the function-count cap. Served by
 * [PhotoScanRepository]; the only DAO consumer is the repository layer.
 */
@Dao
interface PhotoScanDao {
    // The three getUnscannedFor* queries project into [PhotoScanTarget] rather
    // than selecting whole rows: a photo awaiting OCR/CLIP/face work has, by
    // definition, nothing on it worth loading, and `SELECT *` pulled every
    // already-computed clipEmbedding BLOB along with it. ORDER BY date DESC is
    // served by index_Photo_date, so the workers no longer sort in memory.

    /** Not-yet-OCR'd images (skips videos and trashed items). */
    @Query(
        "SELECT id, uri, date, width, height FROM Photo " +
            "WHERE ocrScanned = 0 AND isTrashed = 0 AND duration IS NULL ORDER BY date DESC"
    )
    suspend fun getUnscannedForOCR(): List<PhotoScanTarget>

    @Query(
        "SELECT id, uri, date, width, height FROM Photo " +
            "WHERE faceScanned = 0 AND isTrashed = 0 AND duration IS NULL ORDER BY date DESC"
    )
    suspend fun getUnscannedForFaces(): List<PhotoScanTarget>

    /** Not-yet-CLIP-embedded images (skips videos and trashed items). */
    @Query(
        "SELECT id, uri, date, width, height FROM Photo " +
            "WHERE clipScanned = 0 AND isTrashed = 0 AND duration IS NULL ORDER BY date DESC"
    )
    suspend fun getUnscannedForClip(): List<PhotoScanTarget>

    /** Photos still needing EXIF/XMP parsing. */
    @Query("SELECT id, uri FROM Photo WHERE exifSet = 0 ORDER BY date DESC")
    suspend fun getUnscannedForExif(): List<PhotoExifTarget>

    // ------------------------------------------------------------------
    // Targeted indexing writes
    //
    // Every one of these used to be `upsertAll(listOf(photo.copy(...)))`, which
    // rewrites the whole row — including the multi-KB clipEmbedding — to flip a
    // boolean, and invalidates the Photo table once per photo. They are
    // column-targeted and batched into a single transaction so a scan invalidates
    // once per flush instead of once per item.
    // ------------------------------------------------------------------

    @Query("UPDATE Photo SET ocrScanned = 1 WHERE id IN (:ids)")
    suspend fun setOcrScanned(ids: List<Long>)

    @Query("UPDATE Photo SET faceScanned = 1 WHERE id IN (:ids)")
    suspend fun setFaceScanned(ids: List<Long>)

    @Query("UPDATE Photo SET ocrText = :text, ocrBoxes = :boxes, ocrScanned = 1 WHERE id = :id")
    suspend fun setOcrResult(id: Long, text: String?, boxes: String?)

    @Transaction
    suspend fun setOcrResults(results: List<OcrResult>) {
        for (r in results) setOcrResult(r.id, r.text, r.boxes)
    }

    @Query("UPDATE Photo SET clipEmbedding = :embedding, clipScanned = 1 WHERE id = :id")
    suspend fun setClipResult(id: Long, embedding: ByteArray?)

    @Transaction
    suspend fun setClipResults(results: List<ClipResult>) {
        for (r in results) setClipResult(r.id, r.embedding)
    }

    /**
     * Write parsed EXIF/XMP by column.
     *
     * Deliberately not an upsert: the projection this is driven from
     * ([PhotoExifTarget]) has no clipEmbedding, so upserting a reconstructed row
     * would write NULL over every embedding it touched.
     */
    @Query(
        "UPDATE Photo SET exifSet = 1, lat = :lat, `long` = :long, " +
            "fullWidth = :fullWidth, fullHeight = :fullHeight, " +
            "croppedWidth = :croppedWidth, croppedHeight = :croppedHeight, " +
            "croppedLeft = :croppedLeft, croppedTop = :croppedTop, " +
            "projectionType = :projectionType WHERE id = :id"
    )
    suspend fun setExif(
        id: Long,
        lat: Double?,
        long: Double?,
        fullWidth: Int?,
        fullHeight: Int?,
        croppedWidth: Int?,
        croppedHeight: Int?,
        croppedLeft: Int?,
        croppedTop: Int?,
        projectionType: String?,
    )

    @Transaction
    suspend fun setExifResults(results: List<ExifResult>) {
        for (r in results) {
            setExif(
                id = r.id,
                lat = r.lat,
                long = r.long,
                fullWidth = r.pano?.fullWidth,
                fullHeight = r.pano?.fullHeight,
                croppedWidth = r.pano?.croppedWidth,
                croppedHeight = r.pano?.croppedHeight,
                croppedLeft = r.pano?.croppedLeft,
                croppedTop = r.pano?.croppedTop,
                projectionType = r.pano?.projectionType,
            )
        }
    }

    /** Lightweight (id, embedding) rows for semantic search; excludes trashed. */
    @Query("SELECT id, clipEmbedding FROM Photo WHERE clipEmbedding IS NOT NULL AND isTrashed = 0")
    suspend fun getClipEmbeddings(): List<PhotoEmbedding>

    /** Wipe every stored CLIP embedding (used when the model/version changes). */
    @Query("UPDATE Photo SET clipEmbedding = NULL, clipScanned = 0")
    suspend fun resetClipScanned()

    @Query("UPDATE Photo SET faceScanned = 0")
    suspend fun resetFaceScanned()

    /**
     * Rows predating the mimeType column. While any exist the sync falls back to a
     * full MediaStore scan so they get backfilled.
     */
    @Query("SELECT count(*) FROM Photo WHERE mimeType IS NULL")
    suspend fun countMissingMimeType(): Int

    /** A photo's serialised OCR geometry; NULL if never stored (see [Photo.ocrBoxes]). */
    @Query("SELECT ocrBoxes FROM Photo WHERE id = :id")
    suspend fun getOcrBoxes(id: Long): String?

    /**
     * URIs of untrashed stills, for the home-screen widget's random pick. The
     * widget needs nothing else off the row, and pulling whole rows meant loading
     * every clipEmbedding in the library to choose one photo.
     */
    @Query("SELECT uri FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    suspend fun getStillPhotoUris(): List<String>

    @Query("UPDATE Photo SET ocrBoxes = :json WHERE id = :id")
    suspend fun setOcrBoxes(id: Long, json: String?)
}

/** Lightweight projection of a photo's CLIP embedding for in-memory search. */
data class PhotoEmbedding(
    val id: Long,
    val clipEmbedding: ByteArray,
)

/**
 * The columns an indexing worker needs to open, order and size a photo. Notably
 * excludes clipEmbedding, ocrText and ocrBoxes.
 */
data class PhotoScanTarget(
    val id: Long,
    val uri: String,
    val date: Long,
    val width: Int,
    val height: Int,
)

/** The columns the EXIF backfill needs. */
data class PhotoExifTarget(
    val id: Long,
    val uri: String,
)

/** One photo's OCR output, for the batched write in [PhotoScanDao.setOcrResults]. */
data class OcrResult(
    val id: Long,
    val text: String?,
    val boxes: String?,
)

/** One photo's semantic embedding, for the batched write in [PhotoScanDao.setClipResults]. */
data class ClipResult(
    val id: Long,
    val embedding: ByteArray?,
)

/** One photo's parsed EXIF/XMP, for the batched write in [PhotoScanDao.setExifResults]. */
data class ExifResult(
    val id: Long,
    val lat: Double?,
    val long: Double?,
    val pano: PanoData?,
)

@Database(entities = [Photo::class, Person::class, PhotoFace::class], version = 20, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun photoScanDao(): PhotoScanDao
    abstract fun faceDao(): FaceDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20,
        )
    }
}

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_Photo_date` ON `Photo` (`date`)")
}

val MIGRATION_2_3 = Migration(2, 3) {
    it.execSQL("ALTER TABLE Photo ADD COLUMN dateModified INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_3_4 = Migration(3, 4) {
    it.execSQL("ALTER TABLE Photo ADD COLUMN isTrashed INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_4_5 = Migration(4, 5) {
    it.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `PhotoOCR` USING FTS4(`ocrText` TEXT)")
}

val MIGRATION_5_6 = Migration(5, 6) {
    // Recreate FTS table with new schema including description field
    it.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS `PhotoOCR_new` USING FTS4(`ocrText` TEXT, `description` TEXT)")
    it.execSQL("INSERT INTO PhotoOCR_new(rowid, ocrText, description) SELECT rowid, ocrText, '' FROM PhotoOCR")
    it.execSQL("DROP TABLE PhotoOCR")
    it.execSQL("ALTER TABLE PhotoOCR_new RENAME TO PhotoOCR")
}

val MIGRATION_6_7 = Migration(6, 7) {
    // Optional on-device face recognition: track scanned photos and store face
    // templates for contacts and library photos. SQL mirrors Room's generated
    // schema exactly so schema validation passes.
    it.execSQL("ALTER TABLE Photo ADD COLUMN faceScanned INTEGER NOT NULL DEFAULT 0")
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `ContactFace` (`contactKey` TEXT NOT NULL, `name` TEXT NOT NULL, " +
            "`embedding` BLOB NOT NULL, `photoUri` TEXT NOT NULL, PRIMARY KEY(`contactKey`))"
    )
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`photoId` INTEGER NOT NULL, `embedding` BLOB NOT NULL, `contactKey` TEXT, `contactName` TEXT)"
    )
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_photoId` ON `PhotoFace` (`photoId`)")
}

val MIGRATION_7_8 = Migration(7, 8) {
    // Move from contact-matched faces to unsupervised, unnamed face clustering.
    // Drop the contact table and the old face rows (which carried contact
    // columns), recreate Person (clusters) + PhotoFace (with clusterId), and
    // reset faceScanned so existing photos get re-detected and clustered.
    // Photo data itself is untouched. SQL mirrors Room's generated schema.
    it.execSQL("DROP TABLE IF EXISTS ContactFace")
    it.execSQL("DROP TABLE IF EXISTS PhotoFace")
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `Person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`centroid` BLOB NOT NULL, `faceCount` INTEGER NOT NULL, `repPhotoId` INTEGER NOT NULL, " +
            "`repLeft` REAL NOT NULL, `repTop` REAL NOT NULL, `repRight` REAL NOT NULL, `repBottom` REAL NOT NULL)"
    )
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`photoId` INTEGER NOT NULL, `clusterId` INTEGER NOT NULL, `embedding` BLOB NOT NULL)"
    )
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_photoId` ON `PhotoFace` (`photoId`)")
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_clusterId` ON `PhotoFace` (`clusterId`)")
    it.execSQL("UPDATE Photo SET faceScanned = 0")
}

val MIGRATION_16_17 = Migration(16, 17) {
    // Index the predicate every progress count and every getUnscannedFor* query
    // shares (`isTrashed = 0 AND duration IS NULL`). The only index before this
    // was on `date`, so all of them full-scanned the table — once per observer,
    // on every single write a scan made. No data changes.
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_Photo_isTrashed_duration` ON `Photo` (`isTrashed`, `duration`)")
}

val MIGRATION_17_18 = Migration(17, 18) {
    // Add the MediaStore bucket (BUCKET_DISPLAY_NAME) so the Albums view can
    // group photos. Existing rows stay NULL until the next sync backfills
    // them; the UI shows those as Unknown meanwhile. SQL mirrors Room's
    // generated schema exactly so schema validation passes.
    it.execSQL("ALTER TABLE Photo ADD COLUMN album TEXT")
}

val MIGRATION_18_19 = Migration(18, 19) {
    // Face grouping was rebuilt: faces are aligned on five landmarks (new embeddings),
    // carry a quality flag, and are clustered in one batch pass where a face may belong
    // to no person (clusterId NULL). SQLite cannot relax NOT NULL in place, and every
    // embedding is stale anyway, so both tables are dropped, recreated and re-derived
    // by the background indexer, as in MIGRATION_15_16. Person names are cleared;
    // accepted. SQL mirrors Room's generated schema exactly so schema validation passes.
    it.execSQL("DROP TABLE IF EXISTS Person")
    it.execSQL("DROP TABLE IF EXISTS PhotoFace")
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `Person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`centroid` BLOB NOT NULL, `faceCount` INTEGER NOT NULL, `name` TEXT)"
    )
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`photoId` INTEGER NOT NULL, `clusterId` INTEGER, `embedding` BLOB NOT NULL, " +
            "`clusterable` INTEGER NOT NULL, " +
            "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, " +
            "`srcWidth` INTEGER NOT NULL, `srcHeight` INTEGER NOT NULL)"
    )
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_photoId` ON `PhotoFace` (`photoId`)")
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_clusterId` ON `PhotoFace` (`clusterId`)")
    it.execSQL("UPDATE Photo SET faceScanned = 0")
}

val MIGRATION_19_20 = Migration(19, 20) {
    // Mirror MediaStore's IS_FAVORITE (#912). Existing rows start as not
    // favourite; SyncWorker runs one full MediaStore rescan after this upgrade
    // (see FAVORITES_RESCAN_DONE_KEY) so items already favourited in another
    // gallery app show up. SQL mirrors Room's generated schema exactly.
    it.execSQL("ALTER TABLE Photo ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_8_9 = Migration(8, 9) {
    // Move OCR text off the FTS side-table and onto the Photo row so search is a
    // plain case-insensitive LIKE (no external inference service). ocrScanned
    // mirrors faceScanned so the OCR worker only processes new photos. Photo data
    // itself is untouched. SQL mirrors Room's generated schema exactly.
    it.execSQL("ALTER TABLE Photo ADD COLUMN ocrText TEXT")
    it.execSQL("ALTER TABLE Photo ADD COLUMN ocrScanned INTEGER NOT NULL DEFAULT 0")
    it.execSQL("DROP TABLE IF EXISTS PhotoOCR")
}

val MIGRATION_9_10 = Migration(9, 10) {
    // Add semantic-search columns: store each photo's L2-normalised image
    // embedding (BLOB) plus a clipScanned flag that mirrors ocrScanned so the
    // worker only embeds new photos. OCR text and faces are untouched.
    // SQL mirrors Room's generated schema exactly so schema validation passes.
    it.execSQL("ALTER TABLE Photo ADD COLUMN clipEmbedding BLOB")
    it.execSQL("ALTER TABLE Photo ADD COLUMN clipScanned INTEGER NOT NULL DEFAULT 0")
}

val MIGRATION_10_11 = Migration(10, 11) {
    // Add GPano panorama geometry columns (@Embedded PanoData, all nullable).
    // Reset exifSet so existing photos get re-scanned for GPano XMP on next
    // sync (follows the faceScanned reset precedent in MIGRATION_7_8). Column
    // names mirror Room's generated schema for the embedded fields exactly.
    it.execSQL("ALTER TABLE Photo ADD COLUMN fullWidth INTEGER")
    it.execSQL("ALTER TABLE Photo ADD COLUMN fullHeight INTEGER")
    it.execSQL("ALTER TABLE Photo ADD COLUMN croppedWidth INTEGER")
    it.execSQL("ALTER TABLE Photo ADD COLUMN croppedHeight INTEGER")
    it.execSQL("ALTER TABLE Photo ADD COLUMN croppedLeft INTEGER")
    it.execSQL("ALTER TABLE Photo ADD COLUMN croppedTop INTEGER")
    it.execSQL("UPDATE Photo SET exifSet = 0")
}

val MIGRATION_11_12 = Migration(11, 12) {
    // Add the PanoData.projectionType column so flat (cylindrical) panoramas can
    // be told apart from equirectangular 360 spheres. Existing pano rows (v11
    // only accepted equirectangular) are back-filled as such. Reset exifSet so
    // photos are re-scanned — cylindrical panoramas were previously rejected by
    // the parser and now need to be picked up.
    it.execSQL("ALTER TABLE Photo ADD COLUMN projectionType TEXT")
    it.execSQL("UPDATE Photo SET projectionType = 'equirectangular' WHERE fullWidth IS NOT NULL")
    it.execSQL("UPDATE Photo SET exifSet = 0")
}

val MIGRATION_12_13 = Migration(12, 13) {
    // Add MediaStore's MIME_TYPE, which tells animated GIFs apart from stills.
    // Existing rows stay NULL until the next sync backfills them; see
    // PhotoScanDao.countMissingMimeType.
    it.execSQL("ALTER TABLE Photo ADD COLUMN mimeType TEXT")
}

val MIGRATION_13_14 = Migration(13, 14) {
    // Add per-line OCR geometry so the viewer can overlay selectable text.
    // Deliberately no `UPDATE Photo SET ocrScanned = 0`: re-running OCR over a
    // whole library is expensive, so already-scanned rows stay NULL and the
    // viewer fills them in one photo at a time as they're opened.
    it.execSQL("ALTER TABLE Photo ADD COLUMN ocrBoxes TEXT")
}

val MIGRATION_14_15 = Migration(14, 15) {
    // The detector now returns oriented quads instead of axis-aligned boxes, and
    // recognises rotated text it previously returned garbage for, so the stored
    // geometry is stale and the text is incomplete. Clear the geometry and the
    // scanned flag and let the background indexer rebuild the library; it is
    // already throttled by OCR_INTER_ITEM_DELAY_MS / coolDownBetweenBatches.
    // ocrText is deliberately left in place so search keeps working on whatever
    // was already found while the re-index runs; each row's text is overwritten
    // as it is rescanned.
    it.execSQL("UPDATE Photo SET ocrScanned = 0, ocrBoxes = NULL")
}

val MIGRATION_15_16 = Migration(15, 16) {
    // Faces now store their own geometry, so a cluster's best face can be picked
    // at read time (largest in source pixels) and the viewer can outline faces;
    // Person drops its single representative face and gains a user-chosen name.
    // Existing face rows have no geometry to backfill, so both tables are
    // dropped, recreated and re-derived by the background indexer — the same
    // drop-recreate-reset MIGRATION_7_8 did, for the same reason. Photo, OCR and
    // CLIP data are untouched. Nothing is lost: no names exist before this.
    //
    // Names live on the cluster row, so a future FaceRecognizer.EMBEDDER_VERSION
    // bump (which clears clusters) will clear them too. Accepted.
    //
    // SQL mirrors Room's generated schema exactly so schema validation passes.
    it.execSQL("DROP TABLE IF EXISTS Person")
    it.execSQL("DROP TABLE IF EXISTS PhotoFace")
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `Person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`centroid` BLOB NOT NULL, `faceCount` INTEGER NOT NULL, `name` TEXT)"
    )
    it.execSQL(
        "CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`photoId` INTEGER NOT NULL, `clusterId` INTEGER NOT NULL, `embedding` BLOB NOT NULL, " +
            "`left` REAL NOT NULL, `top` REAL NOT NULL, `right` REAL NOT NULL, `bottom` REAL NOT NULL, " +
            "`srcWidth` INTEGER NOT NULL, `srcHeight` INTEGER NOT NULL)"
    )
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_photoId` ON `PhotoFace` (`photoId`)")
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_clusterId` ON `PhotoFace` (`clusterId`)")
    it.execSQL("UPDATE Photo SET faceScanned = 0")
}
