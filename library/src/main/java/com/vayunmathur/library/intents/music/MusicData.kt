package com.vayunmathur.library.intents.music

import kotlinx.serialization.Serializable

@Serializable
data class MusicSearchResult(val id: Long, val name: String, val type: String)

@Serializable
data class PlayMusicData(val id: Long, val type: String)
