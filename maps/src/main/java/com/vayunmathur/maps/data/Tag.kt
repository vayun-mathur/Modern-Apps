package com.vayunmathur.maps.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Entity
data class AmenityTag(
    @ColumnInfo(index = true)
    val nodeID: Long,
    val key: String,
    val value: String,
    @PrimaryKey(autoGenerate = true) val id: Long = 0
)

@Dao
interface TagDao {
    @Insert
    suspend fun insert(tag: AmenityTag)
    @Insert
    suspend fun insertAll(tags: List<AmenityTag>)
    @Query("SELECT * FROM AmenityTag WHERE nodeID = :nodeID")
    suspend fun getTags(nodeID: Long): List<AmenityTag>
}

/**
 * Represents the Amenities table using 64-bit Geohash integers
 * instead of SpatiaLite geometries.
 */
@Entity(indices = [Index(value = ["lat", "lon"], name = "idx_amenity_coords")])
data class AmenityEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double
)

@Dao
interface AmenityDao {
    // use fts5 search
    @SkipQueryVerification
    @Query("""
        SELECT a.* FROM AmenityEntity a 
        JOIN Amenities_fts f ON a.id = f.rowid 
        WHERE f.name MATCH :query 
          AND a.lat BETWEEN :latMin AND :latMax 
          AND a.lon BETWEEN :lonMin AND :lonMax
    """)
    suspend fun getInBBox(query: String, latMin: Double, lonMin: Double, latMax: Double, lonMax: Double): List<AmenityEntity>
}

@Database(entities = [AmenityTag::class, AmenityEntity::class], version = 1)
abstract class AmenityDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun amenityDao(): AmenityDao
}

fun buildAmenityDatabase(context: Context): AmenityDatabase {
    val dbFile = File(context.getExternalFilesDir(null), "amenities.db")

    return Room.databaseBuilder(
        context,
        AmenityDatabase::class.java,
        dbFile.absolutePath // This name is ignored by our custom factory
    )
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE) // External storage can be flaky with WAL
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Safety check: ensure the file isn't read-only if you need to write
                db.execSQL("PRAGMA synchronous = NORMAL")
            }
        })
        .build()
}