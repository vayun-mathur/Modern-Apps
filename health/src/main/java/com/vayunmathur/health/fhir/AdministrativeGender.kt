package com.vayunmathur.health.fhir

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AdministrativeGender {
    @SerialName("male")
    MALE,

    @SerialName("female")
    FEMALE,

    @SerialName("other")
    OTHER,

    @SerialName("unknown")
    UNKNOWN;

    fun displayString(): String {
        return when(this) {
            MALE -> "Male"
            FEMALE -> "Female"
            OTHER -> "Other"
            UNKNOWN -> "Unknown"
        }
    }
}