package com.vayunmathur.maps.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase

@Fts4(tokenizer = "unicode61")
@Entity(tableName = "name_search")
data class NameSearchEntity(
    @ColumnInfo(name = "value") val name: String,
    @ColumnInfo(name = "nodeID") val nodeID: Long
)
@Dao
interface SearchDao {
    @Insert
    suspend fun insertAll(list: List<NameSearchEntity>)

    @Query("SELECT nodeID FROM name_search WHERE value MATCH :query || '*' LIMIT 50")
    suspend fun search(query: String): List<Long>

    @Query("SELECT COUNT(*) FROM name_search")
    suspend fun getCount(): Long
}

@Database(entities = [NameSearchEntity::class], version = 1)
abstract class TagDatabase : RoomDatabase() {
    abstract fun searchDao(): SearchDao
}
