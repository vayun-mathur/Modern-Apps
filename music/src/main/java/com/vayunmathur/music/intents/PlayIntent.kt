package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.music.data.MusicDatabase
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.music.util.PlaybackManager
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class PlayIntent: AssistantIntent<PlayMusicData, Unit>(serializer<PlayMusicData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: PlayMusicData) {
        val db = buildDatabase<MusicDatabase>()
        val pm = PlaybackManager.getInstance(this)

        val allMusic = db.musicDao().getAll()

        val songsToPlay = when (input.type) {
            "song" -> allMusic.filter { it.id == input.id }
            "album" -> allMusic.filter { it.albumId == input.id }
            "artist" -> allMusic.filter { it.artistId == input.id }
            "playlist" -> {
                val songIds = db.matchingDao().getFromRight(input.id, TYPE_MUSIC_PLAYLIST).toSet()
                allMusic.filter { it.id in songIds }
            }
            else -> emptyList()
        }

        if (songsToPlay.isNotEmpty()) {
            pm.playSong(songsToPlay, 0)
        }
    }
}
