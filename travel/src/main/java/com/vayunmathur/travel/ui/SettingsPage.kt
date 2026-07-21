package com.vayunmathur.travel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedCard
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.data.Customer
import com.vayunmathur.travel.data.FrequentFlyer
import com.vayunmathur.travel.util.TravelViewModel

/**
 * App settings. Currently holds saved frequent-flyer accounts, which are applied
 * as loyalty pricing at flight search and pre-filled at booking.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(backStack: NavBackStack<Route>, viewModel: TravelViewModel) {
    val frequentFlyers by viewModel.frequentFlyers.collectAsStateWithLifecycle()
    val airlines by viewModel.airlines.collectAsStateWithLifecycle()
    val customers by viewModel.customers.collectAsStateWithLifecycle()
    val activeCustomerId by viewModel.activeCustomerId.collectAsStateWithLifecycle()
    val customerError by viewModel.customerError.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadAirlines() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { backStack.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Frequent flyer numbers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Saved numbers are sent when searching so member fares and perks " +
                    "are priced, and are pre-filled when booking.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (frequentFlyers.isEmpty()) {
                Text(
                    "No frequent-flyer numbers saved yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                frequentFlyers.forEach { ff ->
                    FrequentFlyerRow(ff) { viewModel.removeFrequentFlyer(ff.airlineIata) }
                }
            }

            AddFrequentFlyerForm(
                airlines = airlines,
            ) { iata, number, name ->
                viewModel.saveFrequentFlyer(iata, number, name)
            }

            HorizontalDivider()

            Text("Customers", style = MaterialTheme.typography.titleMedium)
            Text(
                "Bookings are associated with the active customer so orders and " +
                    "payments can be tracked per person.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (customers.isEmpty()) {
                Text(
                    "No customers yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                customers.forEach { customer ->
                    CustomerRow(
                        customer = customer,
                        active = customer.id == activeCustomerId,
                        onSelect = { viewModel.selectCustomer(customer.id) },
                        onDelete = { viewModel.removeCustomer(customer.id) },
                    )
                }
            }

            customerError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            AddCustomerForm { email, given, family, phone ->
                viewModel.createCustomer(email, given, family, phone)
            }
        }
    }
}

@Composable
private fun CustomerRow(
    customer: Customer,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(customer.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    customer.email,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilterChip(
                selected = active,
                onClick = onSelect,
                label = { Text(if (active) "Active" else "Use") },
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCustomerForm(
    onAdd: (email: String, given: String, family: String, phone: String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var given by remember { mutableStateOf("") }
    var family by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a customer", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = given,
                onValueChange = { given = it },
                label = { Text("First name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = family,
                onValueChange = { family = it },
                label = { Text("Last name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onAdd(email, given, family, phone)
                email = ""
                given = ""
                family = ""
                phone = ""
            },
            enabled = email.isNotBlank() && given.isNotBlank() && family.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Create customer") }
    }
}

@Composable
private fun FrequentFlyerRow(ff: FrequentFlyer, onDelete: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    ff.airlineName.ifBlank { ff.airlineIata },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${ff.airlineIata} · ${ff.accountNumber}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFrequentFlyerForm(
    airlines: List<com.vayunmathur.travel.network.AirlineDto>,
    onAdd: (iata: String, number: String, name: String) -> Unit,
) {
    var iata by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a number", style = MaterialTheme.typography.titleSmall)
        AirlineDropdown(airlines = airlines, selectedIata = iata, modifier = Modifier.fillMaxWidth()) {
            iata = it
        }
        OutlinedTextField(
            value = number,
            onValueChange = { number = it },
            label = { Text("Account number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val name = airlines.firstOrNull { it.iataCode == iata }?.name.orEmpty()
                onAdd(iata, number, name)
                iata = ""
                number = ""
            },
            enabled = iata.isNotBlank() && number.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
    }
}
