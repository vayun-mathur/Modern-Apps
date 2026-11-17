package com.vayunmathur.travel.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import java.util.UUID
import kotlinx.coroutines.launch

fun TravelViewModel.saveFrequentFlyer(airlineIata: String, accountNumber: String, airlineName: String) {
    val iata = airlineIata.trim().uppercase()
    val account = accountNumber.trim()
    if (iata.isBlank() || account.isBlank()) return
    viewModelScope.launch {
        repository.upsertFrequentFlyer(
            FrequentFlyer(airlineIata = iata, accountNumber = account, airlineName = airlineName),
        )
    }
}

fun TravelViewModel.removeFrequentFlyer(airlineIata: String) {
    viewModelScope.launch { repository.deleteFrequentFlyer(airlineIata) }
}

/**
 * Save a booking profile locally (random UUID id) and report validation
 * errors. Profiles only prefill the passengers form; there is no server use.
 */
fun TravelViewModel.createCustomer(email: String, givenName: String, familyName: String, phone: String) {
    val cleanEmail = email.trim()
    val cleanGiven = givenName.trim()
    val cleanFamily = familyName.trim()
    if (cleanEmail.isBlank() || cleanGiven.isBlank() || cleanFamily.isBlank()) {
        customerErrorMutable.value = "Enter a name and email for this profile."
        return
    }
    viewModelScope.launch {
        repository.upsertCustomer(
            Customer(
                id = UUID.randomUUID().toString(),
                email = cleanEmail,
                givenName = cleanGiven,
                familyName = cleanFamily,
                phoneNumber = phone.trim(),
            )
        )
        customerErrorMutable.value = null
    }
}

fun TravelViewModel.removeCustomer(id: String) {
    viewModelScope.launch { repository.deleteCustomerById(id) }
}
