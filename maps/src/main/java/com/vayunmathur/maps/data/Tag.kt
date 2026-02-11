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

    @Query("""
    SELECT 
        a.id, 
        a.name, 
        a.lat, 
        a.lon, 
        ST_Distance(a.geom, MakePoint(:userLng, :userLat, 4326), 1) AS distanceInMeters
    FROM Amenities a
    -- Filter 1: Full Text Search (Fast)
    JOIN Amenities_fts f ON a.id = f.rowid
    WHERE f.name MATCH :searchQuery
      -- Filter 2: Spatial Index (Fastest way to prune 32M rows)
      AND a.id IN (
        SELECT rowid 
        FROM SpatialIndex 
        WHERE f_table_name = 'Amenities' 
          AND f_geometry_column = 'geom'
          AND search_frame = BuildCircleMbr(:userLng, :userLat, :radiusInDegrees, 4326)
      )
    ORDER BY distanceInMeters ASC
    LIMIT :limit
""")
    @SkipQueryVerification
    suspend fun searchNearby(
        userLat: Double,
        userLng: Double,
        searchQuery: String,
        radiusInDegrees: Double = 0.5,
        limit: Int = 100
    ): List<AmenityEntity>

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