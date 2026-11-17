package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R
import com.vayunmathur.library.ui.R as UiR

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconContacts
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.AirlineDto
import com.vayunmathur.travel.network.OfferPassengerDto
import com.vayunmathur.travel.network.PassengerInputDto
import com.vayunmathur.travel.platform.BookingState
import com.vayunmathur.travel.platform.TravelViewModel
import com.vayunmathur.travel.platform.createTrip
import com.vayunmathur.travel.platform.loadAirlines
import com.vayunmathur.travel.platform.prefillFromProfile
import com.vayunmathur.travel.platform.updatePassenger
import com.vayunmathur.travel.platform.REQUESTED_CONTACT_FIELDS
import com.vayunmathur.travel.platform.readSessionContact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource

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
    val booking by viewModel.booking.collectAsStateWithLifecycle()
    val airlines by viewModel.airlines.collectAsStateWithLifecycle()
    val profiles by viewModel.customers.collectAsStateWithLifecycle()
    val offer = review.offer
    val offerPassengers = offer?.passengers.orEmpty()
    // Passport need is unknown until checkout: show the fields as optional
    // unless the server already demanded them (422 missingFields).
    val missingFields = (booking as? BookingState.MissingDetails)?.missingFields.orEmpty()
    val identityRequired = offer?.passengerIdentityDocumentsRequired == true

    LaunchedEffect(Unit) { viewModel.loadAirlines() }

    // Checkout demanded details the form didn't collect: route back here with
    // the message so the user can fill the gaps.
    val missingMessage = (booking as? BookingState.MissingDetails)?.message

    // Infants that must be linked to an accompanying adult.
    val infantOptions = passengers.mapIndexedNotNull { index, p ->
        val type = offerPassengers.getOrNull(index)?.type
        if (type == "infant_without_seat") p.id to "Infant ${index + 1}" else null
    }
    val linkedInfantIds = passengers.mapNotNull { it.infantPassengerId }.filter { it.isNotBlank() }.toSet()

    AppScaffold(
        title = stringResource(R.string.passengers),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        // Trip creation happens here (not on payment): watch for the trip and
        // move to ancillaries once it exists.
        val tripReady = (booking as? BookingState.TripReady)?.trip
        LaunchedEffect(tripReady) {
            if (tripReady != null) backStack.add(Route.Ancillaries(route.offerId))
        }
        // Single-pane passenger forms; letterboxed on expanded windows.
        DesktopMaxWidthContainer(Modifier.padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (missingMessage != null) {
                Text(
                    missingMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (profiles.isNotEmpty()) {
                ProfilePrefillRow(profiles) {
                    viewModel.prefillFromProfile(it.email, it.givenName, it.familyName, it.phoneNumber)
                }
            }
            passengers.forEachIndexed { index, passenger ->
                PassengerForm(
                    index = index,
                    total = passengers.size,
                    passenger = passenger,
                    passengerRef = offerPassengers.getOrNull(index),
                    identityRequired = identityRequired,
                    identityMissing = missingFields,
                    infantOptions = infantOptions,
                    airlines = airlines,
                    onChange = { viewModel.updatePassenger(index, it) },
                )
            }

            val allInfantsLinked = infantOptions.all { it.first in linkedInfantIds }
            val creating = booking is BookingState.Loading
            (booking as? BookingState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                onClick = { viewModel.createTrip() },
                enabled = passengers.isNotEmpty() &&
                    passengers.indices.all { passengers[it].isValid(offerPassengers.getOrNull(it), identityRequired) } &&
                    allInfantsLinked && !creating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (creating) stringResource(R.string.creating_trip) else stringResource(R.string.continue_to_extras)) }
        }
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
    identityMissing: List<String>,
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
        // The picker only exists from API 37; the guard is repeated here (the button is already
        // hidden below that) so the API-37-only extras are never read on an older platform.
        if (android.os.Build.VERSION.SDK_INT < 37) return
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
                        IconContacts(modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(UiR.string.contacts))
                    }
                }
            }

            ChipRow("Title", TITLES.map { it to it.replaceFirstChar(Char::uppercase) }, passenger.title) {
                onChange(passenger.copy(title = it))
            }

            OutlinedTextField(
                value = passenger.givenName,
                onValueChange = { onChange(passenger.copy(givenName = it)) },
                label = { Text(stringResource(R.string.given_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passenger.familyName,
                onValueChange = { onChange(passenger.copy(familyName = it)) },
                label = { Text(stringResource(R.string.family_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName),
                modifier = Modifier.fillMaxWidth(),
            )

            DateField(
                stringResource(R.string.date_of_birth),
                passenger.bornOn,
                onDate = { onChange(passenger.copy(bornOn = it)) },
                dateFormat = DateString::monthDayYear,
            )

            ChipRow("Gender", GENDERS, passenger.gender) { onChange(passenger.copy(gender = it)) }

            OutlinedTextField(
                value = passenger.email,
                onValueChange = { onChange(passenger.copy(email = it)) },
                label = { Text(if (isAdult) stringResource(UiR.string.email) else stringResource(R.string.email_optional)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passenger.phoneNumber,
                onValueChange = { onChange(passenger.copy(phoneNumber = it)) },
                label = { Text(if (isAdult) stringResource(R.string.phone_e_g_14155550123) else stringResource(R.string.phone_optional)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (identityRequired || identityMissing.isNotEmpty()) {
                HorizontalDivider()
                if (!identityRequired && identityMissing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.passport_may_be_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
