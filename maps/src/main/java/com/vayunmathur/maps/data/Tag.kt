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

/**
 * A fuzzy address-search hit. Not a Room @Entity: the `Addresses` /
 * `Addresses_fts` tables are built server-side (like `Amenities_fts`) and live
 * in the downloaded amenities.db, so they are queried with
 * @SkipQueryVerification and mapped into this plain result class.
 */
data class AddressResult(
    val id: Long,
    val address: String,
    val lat: Double,
    val lon: Double
)

@Dao
interface AddressDao {
    /**
     * Closest-match address search. [query] is an FTS5 expression built from the
     * user's text (prefix tokens joined with OR); bm25() orders rows so the best
     * matches come first.
     */
    @SkipQueryVerification
    @Query("""
        SELECT a.id AS id, a.address AS address, a.lat AS lat, a.lon AS lon
        FROM Addresses a
        JOIN Addresses_fts f ON a.id = f.rowid
        WHERE Addresses_fts MATCH :query
        ORDER BY bm25(Addresses_fts)
        LIMIT :limit
    """)
    suspend fun search(query: String, limit: Int = 50): List<AddressResult>
}

@Database(entities = [AmenityTag::class, AmenityEntity::class], version = 1, exportSchema = false)
abstract class AmenityDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
    abstract fun amenityDao(): AmenityDao
    abstract fun addressDao(): AddressDao
}

fun buildAmenityDatabase(context: Context): AmenityDatabase {
    val dbFile = File(context.getExternalFilesDir(null), "amenities.db")

    // Use `createFromFile` so Room opens the pre-downloaded SQLite file at
    // its real on-disk path. Passing dbFile.absolutePath as the `name`
    // argument (as the previous code did) makes Room interpret the slashes
    // as subdirectory components under /data/data/<pkg>/databases/, which
    // either fails to open or creates an unexpected file. The "name"
    // argument is just a logical identifier in this mode.
    return Room.databaseBuilder(
        context,
        AmenityDatabase::class.java,
        "amenities.db"
    )
        .createFromFile(dbFile)
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE) // External storage can be flaky with WAL
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                db.execSQL("PRAGMA synchronous = NORMAL")
            }
        })
        .build()
}