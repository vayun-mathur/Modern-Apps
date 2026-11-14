package com.vayunmathur.library.intents.findfamily

import kotlinx.serialization.Serializable

/** Serializable family-member location payload for AssistantIntent calls. */
@Serializable
data class FamilyMemberData(val name: String, val locationName: String, val lat: Double, val lon: Double)
