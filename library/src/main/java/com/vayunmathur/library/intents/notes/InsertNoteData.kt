package com.vayunmathur.library.intents.notes

import kotlinx.serialization.Serializable

/** Serializable request to create a note with the given title and content. */
@Serializable
data class InsertNoteData(val title: String, val content: String)
