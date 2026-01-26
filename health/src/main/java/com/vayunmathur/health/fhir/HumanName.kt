package com.vayunmathur.health.fhir

import kotlinx.serialization.Serializable

@Serializable
data class HumanName(val text: String? = null, val family: String? = null, val given: List<String> = emptyList()) {
    fun displayString(): String {
        if(family != null && given.isNotEmpty()) return "$family, ${given.joinToString(" ")}"
        return text ?: ""
    }
}
