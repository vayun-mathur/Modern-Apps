package com.vayunmathur.music.intents

import com.vayunmathur.library.intents.music.PlayMusicData
import com.vayunmathur.library.util.AssistantIntent
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.data.TYPE_MUSIC_PLAYLIST
import com.vayunmathur.music.platform.PlaybackManager
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
class PlayIntent: AssistantIntent<PlayMusicData, Unit>(serializer<PlayMusicData>(), serializer<Unit>()) {

    override suspend fun performCalculation(input: PlayMusicData) {
        val repo = MusicRepository.get(this)
        val pm = PlaybackManager.getInstance(this)

        val allMusic = repo.getAllMusic()

        val playlistIds = if (input.type == "playlist") {
            repo.getFromRight(input.id, TYPE_MUSIC_PLAYLIST)
        } else {
            emptyList()
        }
        val songsToPlay = orderForPlayback(input.type, input.id, allMusic, playlistIds)

        if (songsToPlay.isNotEmpty()) {
            pm.playSong(songsToPlay, 0)
        }
    }
}

/**
 * The queue an assistant "play X" request should start, in playback order.
 *
 * The library list is title-sorted (it backs the songs tab), so filtering
 * it directly played an album alphabetically instead of in disc/track
 * order - the album screen and the car tree both sort albums by
 * disc/track - and played a playlist in library order instead of the
 * order its matchings are stored in.
 */
internal fun orderForPlayback(
    type: String,
    id: Long,
    allMusic: List<Music>,
    playlistIds: List<Long> = emptyList(),
): List<Music> = when (type) {
    "song" -> allMusic.filter { it.id == id }
    "album" -> allMusic.filter { it.albumId == id }
        .sortedWith(compareBy({ it.discNumber }, { it.trackNumber }, { it.title.lowercase() }))
    "artist" -> allMusic.filter { it.artistId == id }
    "playlist" -> {
        val byId = allMusic.associateBy { it.id }
        playlistIds.mapNotNull { byId[it] }
    }
    else -> emptyList()
}
