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
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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

@Database(entities = [AmenityTag::class], version = 1)
abstract class AmenityDatabase : RoomDatabase() {
    abstract fun tagDao(): TagDao
}

class DirectFileOpenHelperFactory(private val path: String) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        // We override the configuration name to be the absolute path
        val directConfig = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(path) // This is the secret sauce: use the absolute path as the name
            .callback(configuration.callback)
            .build()

        return FrameworkSQLiteOpenHelperFactory().create(directConfig)
    }
}

fun buildAmenityDatabase(context: Context): AmenityDatabase {
    val dbFile = File(context.getExternalFilesDir(null), "amenities.db")

    return Room.databaseBuilder(
        context,
        AmenityDatabase::class.java,
        "amenities.db" // This name is ignored by our custom factory
    )
        .openHelperFactory(DirectFileOpenHelperFactory(dbFile.absolutePath))
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