package com.vayunmathur.health.data
import kotlinx.serialization.Serializable

@Serializable
data class Address(val text: String? = null) {
    fun displayString(): String {
        return text ?: ""
    }
}
