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

    @Query("SELECT Photo.* FROM Photo JOIN PhotoOCR ON Photo.id = PhotoOCR.rowid WHERE PhotoOCR MATCH :query AND Photo.isTrashed = 0")
    suspend fun searchPhotos(query: String): List<Photo>

    @Upsert
    suspend fun upsertOCR(ocr: PhotoOCR)

    @Query("DELETE FROM PhotoOCR WHERE rowid IN (:ids)")
    suspend fun deleteOCRByIds(ids: List<Long>)

    @Query("SELECT count(*) FROM PhotoOCR")
    fun getOCRCountFlow(): Flow<Int>

    @Query("SELECT count(*) FROM Photo WHERE isTrashed = 0 AND duration IS NULL")
    fun getOCRTargetCountFlow(): Flow<Int>

    @Query("SELECT * FROM Photo WHERE faceScanned = 0 AND isTrashed = 0 AND duration IS NULL")
    suspend fun getUnscannedForFaces(): List<Photo>

    @Query("UPDATE Photo SET faceScanned = 0")
    suspend fun resetFaceScanned()
}

@Database(entities = [Photo::class, PhotoOCR::class, ContactFace::class, PhotoFace::class], version = 7, exportSchema = false)
abstract class PhotoDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun faceDao(): FaceDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
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
