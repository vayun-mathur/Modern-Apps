package com.vayunmathur.music.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

@Dao
interface MusicDao {
    @Query("SELECT * FROM Music")
    fun getAllFlow(): Flow<List<Music>>
    @Query("SELECT * FROM Music")
    suspend fun getAll(): List<Music>
    @Upsert
    suspend fun upsertAll(items: List<Music>)
    @Query("DELETE FROM Music")
    suspend fun deleteAll()
    @Query("DELETE FROM Music WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM Album")
    fun getAllFlow(): Flow<List<Album>>
    @Query("SELECT * FROM Album")
    suspend fun getAll(): List<Album>
    @Upsert
    suspend fun upsertAll(items: List<Album>)
    @Query("DELETE FROM Album")
    suspend fun deleteAll()
}

@Dao
interface ArtistDao {
    @Query("SELECT * FROM Artist")
    fun getAllFlow(): Flow<List<Artist>>
    @Query("SELECT * FROM Artist")
    suspend fun getAll(): List<Artist>
    @Upsert
    suspend fun upsertAll(items: List<Artist>)
    @Query("DELETE FROM Artist")
    suspend fun deleteAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM Playlist")
    fun getAllFlow(): Flow<List<Playlist>>
    @Query("SELECT * FROM Playlist")
    suspend fun getAll(): List<Playlist>
    @Upsert
    suspend fun upsert(value: Playlist): Long
    @Query("DELETE FROM Playlist WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@TypeConverters(DefaultConverters::class)
@Database(entities = [Music::class, Album::class, Artist::class, Playlist::class, ManyManyMatching::class], version = 3, exportSchema = false)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun matchingDao(): MatchingDao

    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> = listOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Many-to-many matching type codes. Indices are Music=0, Album=1, Artist=2,
// Playlist=3, and a type code is `min(a,b) + 100*max(a,b)`. The "left" side
// of a row in `ManyManyMatching` is the entity with the smaller index.
// ─────────────────────────────────────────────────────────────────────────────
const val TYPE_MUSIC_PLAYLIST: Int = 0 + 100 * 3    // 300, left=Music,  right=Playlist
const val TYPE_ALBUM_ARTIST: Int = 1 + 100 * 2      // 201, left=Album,  right=Artist

val MIGRATION_1_2 = Migration(1, 2) {
    it.execSQL(
        """
        CREATE TABLE IF NOT EXISTS `Playlist` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, 
            `name` TEXT NOT NULL
        )
        """.trimIndent()
    )
}

val MIGRATION_2_3 = Migration(2, 3) {
    it.execSQL("ALTER TABLE Music ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
    it.execSQL("ALTER TABLE Music ADD COLUMN trackNumber INTEGER NOT NULL DEFAULT 0")
    it.execSQL("ALTER TABLE Music ADD COLUMN year INTEGER NOT NULL DEFAULT 0")
}
