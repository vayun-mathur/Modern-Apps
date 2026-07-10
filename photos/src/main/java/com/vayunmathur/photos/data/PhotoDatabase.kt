package com.vayunmathur.photos.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM Photo")
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

    @Query("SELECT * FROM Photo WHERE isTrashed = 0 AND (ocrText LIKE '%' || :query || '%' OR name LIKE '%' || :query || '%') ORDER BY date DESC")
    suspend fun searchPhotos(query: String): List<Photo>

    /** Photos already scanned by OCR (numerator of the search-index progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE ocrScanned = 1 AND isTrashed = 0 AND duration IS NULL")
    fun getOCRCountFlow(): Flow<Int>

    /** Photos that count toward OCR indexing (denominator of the progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getOCRTargetCountFlow(): Flow<Int>

    /** Not-yet-OCR'd images (skips videos and trashed items). */
    @Query("SELECT * FROM Photo WHERE ocrScanned = 0 AND isTrashed = 0 AND duration IS NULL")
    suspend fun getUnscannedForOCR(): List<Photo>

    @Query("SELECT * FROM Photo WHERE faceScanned = 0 AND isTrashed = 0 AND duration IS NULL")
    suspend fun getUnscannedForFaces(): List<Photo>

    /** Not-yet-CLIP-embedded images (skips videos and trashed items). */
    @Query("SELECT * FROM Photo WHERE clipScanned = 0 AND isTrashed = 0 AND duration IS NULL")
    suspend fun getUnscannedForClip(): List<Photo>

    /** Photos with a stored embedding, i.e. actually indexed (progress numerator). */
    @Query("SELECT count(*) FROM Photo WHERE clipEmbedding IS NOT NULL AND isTrashed = 0 AND duration IS NULL")
    fun getClipCountFlow(): Flow<Int>

    /** Photos that count toward CLIP embedding (denominator of the progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getClipTargetCountFlow(): Flow<Int>

    /** Lightweight (id, embedding) rows for semantic search; excludes trashed. */
    @Query("SELECT id, clipEmbedding FROM Photo WHERE clipEmbedding IS NOT NULL AND isTrashed = 0")
    suspend fun getClipEmbeddings(): List<PhotoEmbedding>

    /** Wipe every stored CLIP embedding (used when the model/version changes). */
    @Query("UPDATE Photo SET clipEmbedding = NULL, clipScanned = 0")
    suspend fun resetClipScanned()

    /** Photos that count toward face indexing (denominator of the progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getFaceTargetCountFlow(): Flow<Int>

    /** Photos already scanned for faces (numerator of the progress bar). */
    @Query("SELECT count(*) FROM Photo WHERE faceScanned = 1 AND isTrashed = 0 AND duration IS NULL")
    fun getFaceScannedCountFlow(): Flow<Int>

    @Query("UPDATE Photo SET faceScanned = 0")
    suspend fun resetFaceScanned()
}

/** Lightweight projection of a photo's CLIP embedding for in-memory search. */
data class PhotoEmbedding(
    val id: Long,
    val clipEmbedding: ByteArray,
)

@Database(entities = [Photo::class, Person::class, PhotoFace::class], version = 11, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun faceDao(): FaceDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
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
    it.execSQL("CREATE TABLE IF NOT EXISTS `ContactFace` (`contactKey` TEXT NOT NULL, `name` TEXT NOT NULL, `embedding` BLOB NOT NULL, `photoUri` TEXT NOT NULL, PRIMARY KEY(`contactKey`))")
    it.execSQL("CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoId` INTEGER NOT NULL, `embedding` BLOB NOT NULL, `contactKey` TEXT, `contactName` TEXT)")
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
    it.execSQL("CREATE TABLE IF NOT EXISTS `Person` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `centroid` BLOB NOT NULL, `faceCount` INTEGER NOT NULL, `repPhotoId` INTEGER NOT NULL, `repLeft` REAL NOT NULL, `repTop` REAL NOT NULL, `repRight` REAL NOT NULL, `repBottom` REAL NOT NULL)")
    it.execSQL("CREATE TABLE IF NOT EXISTS `PhotoFace` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `photoId` INTEGER NOT NULL, `clusterId` INTEGER NOT NULL, `embedding` BLOB NOT NULL)")
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_photoId` ON `PhotoFace` (`photoId`)")
    it.execSQL("CREATE INDEX IF NOT EXISTS `index_PhotoFace_clusterId` ON `PhotoFace` (`clusterId`)")
    it.execSQL("UPDATE Photo SET faceScanned = 0")
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
