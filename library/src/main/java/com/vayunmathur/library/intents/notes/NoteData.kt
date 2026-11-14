package com.vayunmathur.library.intents.notes

import kotlinx.serialization.Serializable

/** Serializable note payload (title and content) exchanged over AssistantIntent. */
@Serializable
data class NoteData(val title: String, val content: String)
