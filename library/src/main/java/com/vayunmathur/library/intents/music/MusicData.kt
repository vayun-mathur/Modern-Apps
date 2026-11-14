package com.vayunmathur.library.intents.music

import kotlinx.serialization.Serializable

/** Serializable music search hit (id, name, kind) returned over AssistantIntent. */
@Serializable
data class MusicSearchResult(val id: Long, val name: String, val type: String)

/** Serializable request to play a music item by id and kind. */
@Serializable
data class PlayMusicData(val id: Long, val type: String)
