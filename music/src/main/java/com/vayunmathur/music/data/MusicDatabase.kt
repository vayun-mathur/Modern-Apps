package com.vayunmathur.music.data
import androidx.room3.ColumnTypeConverters
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Upsert
import androidx.sqlite.execSQL
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import androidx.room3.migration.Migration
import kotlinx.coroutines.flow.Flow

/** Contract for music dao. */
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

/** Contract for playlist dao. */
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

/**
 * Playlists, the playlist-to-song matchings, and a cache of the tag data MediaStore does not have.
 *
 * Songs, albums and artists are **not** stored here: they come from MediaStore on every refresh (see
 * [com.vayunmathur.music.data.MusicRepository]). The `Music` table survives only as a cache of
 * `duration` and `year` for files whose MediaStore rows leave those blank, because recovering them
 * means opening each file with a `MediaMetadataRetriever` and we only want to pay that once.
 */
@ColumnTypeConverters(DefaultConverters::class)
@Database(entities = [Music::class, Playlist::class, ManyManyMatching::class], version = 5, exportSchema = true)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun matchingDao(): MatchingDao

    /** Shared constants and helpers for MusicDatabase. */
    companion object : com.vayunmathur.library.util.DatabaseMigrations {
        override val migrations: List<Migration> =
            listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

val MIGRATION_3_4 = Migration(3, 4) {
    it.execSQL("ALTER TABLE Music ADD COLUMN discNumber INTEGER NOT NULL DEFAULT 1")
}

/**
 * Albums and artists moved to MediaStore, which is where they came from in the first place; the
 * mirrored tables were only ever a copy that had to be kept in sync.
 *
 * The derived album-to-artist matchings go with them - they are recomputed in memory from the song
 * list on every refresh. Playlist matchings (`TYPE_MUSIC_PLAYLIST`) are the user's own data and are
 * deliberately left alone.
 */
val MIGRATION_4_5 = Migration(4, 5) {
    it.execSQL("DROP TABLE IF EXISTS Album")
    it.execSQL("DROP TABLE IF EXISTS Artist")
    it.execSQL("DELETE FROM ManyManyMatching WHERE type = $TYPE_ALBUM_ARTIST")
}
