package com.vayunmathur.maps.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import co.anbora.labs.spatia.builder.SpatiaRoom
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

@Entity(tableName = "Amenities")
data class AmenityEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double
)

/**
 * A helper class to capture the search results along with the
 * distance calculated by SpatiaLite.
 */
data class AmenitySearchResult(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val distanceInMeters: Double
)

@Dao
interface AmenityDao {

    /**
     * The High-Speed Search.
     * 1. Joins with the FTS5 table for instant text matching.
     * 2. Uses the 'knn' virtual table to utilize the R-Tree index.
     * * @param searchQuery The text to match (e.g., "Starbucks")
     * @param userLat Current user latitude
     * @param userLng Current user longitude
     * @param limit Maximum number of results to return
     */
    @SkipQueryVerification
    @Query("""
        SELECT 
            a.id, 
            a.name, 
            a.lat, 
            a.lon, 
            ST_Distance(a.geom, MakePoint(:userLng, :userLat, 4326), 1) AS distanceInMeters
        FROM knn k
        JOIN Amenities a ON k.fid = a.id
        JOIN Amenities_fts f ON a.id = f.rowid
        WHERE f_table_name = 'Amenities' 
          AND f_geometry_column = 'geom'
          AND ref_geometry = MakePoint(:userLng, :userLat, 4326)
          AND max_items = 100
          AND f.name MATCH :searchQuery
        ORDER BY distanceInMeters ASC
        LIMIT :limit
    """)
    suspend fun findNearestAmenities(
        searchQuery: String,
        userLat: Double,
        userLng: Double,
        limit: Int = 15
    ): List<AmenitySearchResult>

    /**
     * Fallback search for when the user hasn't typed anything yet (Browsing mode).
     * This skips FTS and purely uses the R-Tree KNN search.
     */
    @SkipQueryVerification
    @Query("""
        SELECT 
            a.id, 
            a.name, 
            a.lat, 
            a.lon, 
            ST_Distance(a.geom, MakePoint(:userLng, :userLat, 4326), 1) AS distanceInMeters
        FROM knn k
        JOIN Amenities a ON k.fid = a.id
        WHERE f_table_name = 'Amenities' 
          AND f_geometry_column = 'geom'
          AND ref_geometry = MakePoint(:userLng, :userLat, 4326)
          AND max_items = :limit
    """)
    suspend fun getNearest(
        userLat: Double,
        userLng: Double,
        limit: Int = 15
    ): List<AmenitySearchResult>

    @Query("SELECT * FROM Amenities WHERE id = :id")
    suspend fun getById(id: Long): AmenityEntity?
}

@Database(entities = [AmenityTag::class, AmenityEntity::class], version = 1)
abstract class AmenityDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun amenityDao(): AmenityDao
}

fun buildAmenityDatabase(context: Context): AmenityDatabase {
    val dbFile = File(context.getExternalFilesDir(null), "amenities.db")

    return SpatiaRoom.databaseBuilder(
        context,
        AmenityDatabase::class.java,
        "amenities.db" // This name is ignored by our custom factory
    )
        .createFromFile(dbFile)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE) // External storage can be flaky with WAL
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Safety check: ensure the file isn't read-only if you need to write
                db.execSQL("PRAGMA synchronous = NORMAL")

                db.query("SELECT spatialite_version()").use { cursor ->
                    if (cursor.moveToFirst()) {
                        android.util.Log.d("SpatiaLite", "Loaded version: ${cursor.getString(0)}")
                    }
                }
            }
        })
        .build()
}