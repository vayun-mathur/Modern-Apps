package com.vayunmathur.music.data
import androidx.room.Dao
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.vayunmathur.library.util.DefaultConverters
import com.vayunmathur.library.util.ManyManyMatching
import com.vayunmathur.library.util.MatchingDao
import com.vayunmathur.library.util.TrueDao

@Dao
interface MusicDao: TrueDao<Music>
@Dao
interface AlbumDao: TrueDao<Album>
@Dao
interface ArtistDao: TrueDao<Artist>
@Dao
interface PlaylistDao: TrueDao<Playlist>

@TypeConverters(DefaultConverters::class)
@Database(entities = [Music::class, Album::class, Artist::class, Playlist::class, ManyManyMatching::class], version = 2)
abstract class MusicDatabase: RoomDatabase() {
    abstract fun musicDao(): MusicDao
    abstract fun albumDao(): AlbumDao
    abstract fun artistDao(): ArtistDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun matchingDao(): MatchingDao
}