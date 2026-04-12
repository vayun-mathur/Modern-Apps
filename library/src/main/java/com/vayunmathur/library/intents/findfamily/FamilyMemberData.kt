package com.vayunmathur.library.intents.findfamily

import kotlinx.serialization.Serializable

@Serializable
data class FamilyMemberData(val name: String, val locationName: String, val lat: Double, val lon: Double)
