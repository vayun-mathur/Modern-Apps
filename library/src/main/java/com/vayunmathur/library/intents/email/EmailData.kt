package com.vayunmathur.library.intents.email

import kotlinx.serialization.Serializable

@Serializable
data class EmailData(
    val subject: String,
    val from: String,
    val to: String? = null,
    val date: String,
    val body: String? = null,
    val isRead: Boolean
)

@Serializable
data class EmailSearchQuery(val query: String)
