package com.vayunmathur.library.intents.calendar

import kotlinx.serialization.Serializable

@Serializable
data class EventData(val title: String, val start: Long, val end: Long, val location: String = "")
