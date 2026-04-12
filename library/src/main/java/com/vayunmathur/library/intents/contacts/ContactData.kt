package com.vayunmathur.library.intents.contacts

import kotlinx.serialization.Serializable

@Serializable
data class ContactData(val name: String, val phoneNumber: String)
