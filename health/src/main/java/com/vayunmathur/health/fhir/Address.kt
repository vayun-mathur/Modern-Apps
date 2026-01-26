package com.vayunmathur.health.fhir

import kotlinx.serialization.Serializable

@Serializable
data class Address(val text: String? = null) {
    fun displayString(): String {
        return text ?: ""
    }
}
