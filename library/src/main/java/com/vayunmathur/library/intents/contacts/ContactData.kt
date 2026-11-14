package com.vayunmathur.library.intents.contacts

import kotlinx.serialization.Serializable

/** Serializable contact payload (name and phone number) for AssistantIntent calls. */
@Serializable
data class ContactData(val name: String, val phoneNumber: String)
