package com.vayunmathur.travel.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
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
                    onChange = { viewModel.updatePassenger(index, it) },
                )
            }

            Button(
                onClick = { backStack.add(Route.Payment(route.offerId)) },
                enabled = passengers.isNotEmpty() && passengers.all { it.isValid() },
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
    onChange: (PassengerInputDto) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Android 17+ field-scoped picker: grants read for only the requested MIME
    // types on the returned session URI — no READ_CONTACTS permission needed.
    // Autofill is only offered on API 37+; older devices enter details manually.
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
                    if (total > 1) "Passenger ${index + 1}" else "Passenger details",
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
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passenger.phoneNumber,
                onValueChange = { onChange(passenger.copy(phoneNumber = it)) },
                label = { Text("Phone (e.g. +14155550123)") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
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

private fun PassengerInputDto.isValid(): Boolean =
    title.isNotBlank() &&
        givenName.isNotBlank() &&
        familyName.isNotBlank() &&
        bornOn.isNotBlank() &&
        gender.isNotBlank() &&
        email.contains("@") &&
        phoneNumber.isNotBlank()
