package com.vayunmathur.health.fhir

import kotlinx.serialization.Serializable

@Serializable
data class Patient(val name: List<HumanName> = emptyList(), val gender: AdministrativeGender? = null, val birthDate: Date? = null, val address: List<Address> = listOf())