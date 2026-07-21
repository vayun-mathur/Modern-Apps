package com.vayunmathur.travel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuAnchorType
import com.vayunmathur.library.ui.ExposedDropdownMenuBox
import com.vayunmathur.library.ui.ExposedDropdownMenu
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.IdentityDocumentDto
import com.vayunmathur.travel.network.LoyaltyAccountDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.REQUESTED_CONTACT_FIELDS
import com.vayunmathur.travel.util.readSessionContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TITLES = listOf("mr", "ms", "mrs", "miss")
private val GENDERS = listOf("m" to "Male", "f" to "Female")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassengersPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
    route: Route.Passengers,
) {
    val passengers by viewModel.passengers.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val airlines by viewModel.airlines.collectAsStateWithLifecycle()
    val offer = review.offer
    val offerPassengers = offer?.passengers.orEmpty()
    val identityRequired = offer?.passengerIdentityDocumentsRequired == true

    LaunchedEffect(Unit) { viewModel.loadAirlines() }

    // Infants that must be linked to an accompanying adult.
    val infantOptions = passengers.mapIndexedNotNull { index, p ->
        val type = offerPassengers.getOrNull(index)?.type
        if (type == "infant_without_seat") p.id to "Infant ${index + 1}" else null
    }
    val linkedInfantIds = passengers.mapNotNull { it.infantPassengerId }.filter { it.isNotBlank() }.toSet()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passengers") },
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
            passengers.forEachIndexed { index, passenger ->
                PassengerForm(
                    index = index,
                    total = passengers.size,
                    passenger = passenger,
                    passengerRef = offerPassengers.getOrNull(index),
                    identityRequired = identityRequired,
                    infantOptions = infantOptions,
                    airlines = airlines,
                    onChange = { viewModel.updatePassenger(index, it) },
                )
            }

            val allInfantsLinked = infantOptions.all { it.first in linkedInfantIds }
            Button(
                onClick = { backStack.add(Route.Payment(route.offerId)) },
                enabled = passengers.isNotEmpty() &&
                    passengers.indices.all { passengers[it].isValid(offerPassengers.getOrNull(it), identityRequired) } &&
                    allInfantsLinked,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue to payment") }
        }
    }
}

@Composable
private fun PassengerForm(
    index: Int,
    total: Int,
    passenger: PassengerInputDto,
    passengerRef: OfferPassengerDto?,
    identityRequired: Boolean,
    infantOptions: List<Pair<String, String>>,
    airlines: List<AirlineDto>,
    onChange: (PassengerInputDto) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = passengerRef?.type ?: "adult"
    val isAdult = type == "adult"

    val canImportContacts = android.os.Build.VERSION.SDK_INT >= 37
    val sessionPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
            scope.launch {
                val info = withContext(Dispatchers.IO) { readSessionContact(context, uri) }
                if (info != null) {
                    onChange(
                        passenger.copy(
                            givenName = info.givenName.ifBlank { passenger.givenName },
                            familyName = info.familyName.ifBlank { passenger.familyName },
                            email = info.email.ifBlank { passenger.email },
                            phoneNumber = info.phone.ifBlank { passenger.phoneNumber },
                            bornOn = info.bornOn.ifBlank { passenger.bornOn },
                        )
                    )
                }
            }
        }
    }

    fun importFromContacts() {
        val intent = android.content.Intent(
            android.provider.ContactsPickerSessionContract.ACTION_PICK_CONTACTS
        ).apply {
            putStringArrayListExtra(
                android.provider.ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_REQUESTED_DATA_FIELDS,
                ArrayList(REQUESTED_CONTACT_FIELDS),
            )
            putExtra(
                android.provider.ContactsPickerSessionContract.EXTRA_PICK_CONTACTS_SELECTION_LIMIT, 1,
            )
        }
        runCatching { sessionPicker.launch(intent) }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    passengerLabel(index, total, passengerRef),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (canImportContacts) {
                    OutlinedButton(onClick = { importFromContacts() }) {
                        Icon(
                            Icons.Filled.Contacts,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text("Contacts")
                    }
                }
            }

            ChipRow("Title", TITLES.map { it to it.replaceFirstChar(Char::uppercase) }, passenger.title) {
                onChange(passenger.copy(title = it))
            }

            OutlinedTextField(
                value = passenger.givenName,
                onValueChange = { onChange(passenger.copy(givenName = it)) },
                label = { Text("Given name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passenger.familyName,
                onValueChange = { onChange(passenger.copy(familyName = it)) },
                label = { Text("Family name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            DateField(
                "Date of birth",
                passenger.bornOn,
                onDate = { onChange(passenger.copy(bornOn = it)) },
                dateFormat = "MMM d, yyyy",
            )

            ChipRow("Gender", GENDERS, passenger.gender) { onChange(passenger.copy(gender = it)) }

            OutlinedTextField(
                value = passenger.email,
                onValueChange = { onChange(passenger.copy(email = it)) },
                label = { Text(if (isAdult) "Email" else "Email (optional)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passenger.phoneNumber,
                onValueChange = { onChange(passenger.copy(phoneNumber = it)) },
                label = { Text(if (isAdult) "Phone (e.g. +14155550123)" else "Phone (optional)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (identityRequired) {
                HorizontalDivider()
                IdentityDocumentFields(passenger.identityDocument) {
                    onChange(passenger.copy(identityDocument = it))
                }
            }

            HorizontalDivider()
            LoyaltyFields(passenger.loyaltyProgrammeAccounts.firstOrNull(), airlines) { acct ->
                onChange(
                    passenger.copy(
                        loyaltyProgrammeAccounts = if (acct == null) emptyList() else listOf(acct),
                    )
                )
            }

            if (isAdult && infantOptions.isNotEmpty()) {
                HorizontalDivider()
                InfantLinkField(
                    selected = passenger.infantPassengerId,
                    infantOptions = infantOptions,
                    onSelect = { onChange(passenger.copy(infantPassengerId = it)) },
                )
            }
        }
    }
}

@Composable
private fun IdentityDocumentFields(doc: IdentityDocumentDto?, onChange: (IdentityDocumentDto) -> Unit) {
    val current = doc ?: IdentityDocumentDto()
    Text(
        "Passport",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = current.uniqueIdentifier,
        onValueChange = { onChange(current.copy(uniqueIdentifier = it)) },
        label = { Text("Passport number") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = current.issuingCountryCode,
        onValueChange = { onChange(current.copy(issuingCountryCode = it.uppercase().take(2))) },
        label = { Text("Issuing country (e.g. GB)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    DateField(
        "Expiry date",
        current.expiresOn,
        onDate = { onChange(current.copy(expiresOn = it)) },
        dateFormat = "MMM d, yyyy",
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoyaltyFields(
    account: LoyaltyAccountDto?,
    airlines: List<AirlineDto>,
    onChange: (LoyaltyAccountDto?) -> Unit,
) {
    val current = account ?: LoyaltyAccountDto()
    Text(
        "Frequent flyer (optional)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AirlineDropdown(
            airlines = airlines,
            selectedIata = current.airlineIataCode,
            modifier = Modifier.weight(1f),
        ) { iata ->
            val next = current.copy(airlineIataCode = iata)
            onChange(if (next.airlineIataCode.isBlank() && next.accountNumber.isBlank()) null else next)
        }
        OutlinedTextField(
            value = current.accountNumber,
            onValueChange = {
                val next = current.copy(accountNumber = it)
                onChange(if (next.airlineIataCode.isBlank() && next.accountNumber.isBlank()) null else next)
            },
            label = { Text("Number") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Searchable airline picker: the user types to filter by name or IATA code and
 * taps a result. Falls back to a plain 2-letter code field until the reference
 * list has loaded (or if it failed to load).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirlineDropdown(
    airlines: List<AirlineDto>,
    selectedIata: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    if (airlines.isEmpty()) {
        OutlinedTextField(
            value = selectedIata,
            onValueChange = { onSelect(it.uppercase().take(2)) },
            label = { Text("Airline") },
            singleLine = true,
            modifier = modifier,
        )
        return
    }
    val selectedName = airlines.firstOrNull { it.iataCode == selectedIata }?.name ?: selectedIata
    var expanded by remember { mutableStateOf(false) }
    var query by remember(selectedName) { mutableStateOf(selectedName) }
    val filtered = remember(query, airlines) {
        if (query.isBlank()) {
            airlines
        } else {
            airlines.filter {
                it.name.contains(query, ignoreCase = true) || it.iataCode.contains(query, ignoreCase = true)
            }
        }.take(60)
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
                if (it.isBlank()) onSelect("")
            },
            label = { Text("Airline") },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .fillMaxWidth(),
        )
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                filtered.forEach { airline ->
                    DropdownMenuItem(
                        text = { Text("${airline.name} (${airline.iataCode})") },
                        onClick = {
                            onSelect(airline.iataCode)
                            query = airline.name
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InfantLinkField(
    selected: String?,
    infantOptions: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Accompanying infant",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = selected.isNullOrBlank(), onClick = { onSelect(null) }, label = { Text("None") })
            infantOptions.forEach { (id, label) ->
                FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ChipRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

private fun passengerLabel(index: Int, total: Int, ref: OfferPassengerDto?): String {
    val kind = when (ref?.type) {
        "child" -> ref.age?.let { "Child ($it)" } ?: "Child"
        "infant_without_seat" -> "Infant"
        else -> "Adult"
    }
    return if (total > 1) "$kind ${index + 1}" else "$kind"
}

private fun PassengerInputDto.isValid(ref: OfferPassengerDto?, identityRequired: Boolean): Boolean {
    val isAdult = (ref?.type ?: "adult") == "adult"
    val base = title.isNotBlank() &&
        givenName.isNotBlank() &&
        familyName.isNotBlank() &&
        bornOn.isNotBlank() &&
        gender.isNotBlank()
    val contact = !isAdult || (email.contains("@") && phoneNumber.isNotBlank())
    val identity = !identityRequired || (
        identityDocument != null &&
            identityDocument.uniqueIdentifier.isNotBlank() &&
            identityDocument.issuingCountryCode.isNotBlank() &&
            identityDocument.expiresOn.isNotBlank()
        )
    return base && contact && identity
}
