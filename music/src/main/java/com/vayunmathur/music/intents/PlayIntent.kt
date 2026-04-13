package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.buildDatabase
import com.vayunmathur.music.util.PlaybackManager
import com.vayunmathur.music.data.Album
import com.vayunmathur.music.data.Artist
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.Playlist
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class PlayIntent: AssistantIntent<PlayMusicData, Unit>(serializer<PlayMusicData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: PlayMusicData) {
        val db = buildDatabase<MusicDatabase>()
        val viewModel = DatabaseViewModel(db, Music::class to db.musicDao(), Album::class to db.albumDao(), Artist::class to db.artistDao(), Playlist::class to db.playlistDao(), matchingDao = db.matchingDao())
        val pm = PlaybackManager.getInstance(this)
        
        val allMusic = viewModel.getAll<Music>()
        
        val songsToPlay = when (input.type) {
            "song" -> allMusic.filter { it.id == input.id }
            "album" -> allMusic.filter { it.albumId == input.id }
            "artist" -> allMusic.filter { it.artistId == input.id }
            "playlist" -> {
                val songIds = viewModel.getMatches<Playlist, Music>(input.id)
                allMusic.filter { songIds.contains(it.id) }
            }
            else -> emptyList()
        }
        
        if (songsToPlay.isNotEmpty()) {
            pm.playSong(songsToPlay, 0)
        }
    }
}
