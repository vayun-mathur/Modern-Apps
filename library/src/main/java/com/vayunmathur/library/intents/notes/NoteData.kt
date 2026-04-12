package com.vayunmathur.library.intents.notes

import kotlinx.serialization.Serializable

@Serializable
data class NoteData(val title: String, val content: String)
