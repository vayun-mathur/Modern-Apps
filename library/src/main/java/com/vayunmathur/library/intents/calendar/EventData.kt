package com.vayunmathur.library.intents.calendar

import kotlinx.serialization.Serializable

/** Serializable calendar-event payload carried across an AssistantIntent boundary. */
@Serializable
data class EventData(val title: String, val start: Long, val end: Long, val location: String = "")
