package com.vayunmathur.shop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.platform.ShopViewModel

/** Buyer profile. With [required] it is the first-run gate and the rest of the app stays locked until saved. */
@Composable
fun ProfileScreen(viewModel: ShopViewModel, required: Boolean) {
    val messenger = rememberMessenger()
    val saved = viewModel.profile
    var draft by remember(saved) { mutableStateOf(saved ?: ShopProfile()) }
    val error = draft.validationError()
    AppScaffold(title = "Profile", scrollBehavior = appBarScrollBehavior()) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (required) Text("Add your details to start shopping.", style = MaterialTheme.typography.titleMedium)
                Text("Encrypted on this device. Checkout uses these details and sends them to a merchant only when you create or update a session.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                ProfileField("First name", draft.firstName) { draft = draft.copy(firstName = it) }
                ProfileField("Last name", draft.lastName) { draft = draft.copy(lastName = it) }
                ProfileField("Email", draft.email, KeyboardType.Email) { draft = draft.copy(email = it) }
                ProfileField("Phone (international, e.g. +14155550123)", draft.phone, KeyboardType.Phone) { draft = draft.copy(phone = it) }
            }
            item {
                Text("Delivery address (optional)", style = MaterialTheme.typography.titleSmall)
                Text("Needed when a merchant ships your order.", style = MaterialTheme.typography.bodySmall)
                ProfileField("Street address", draft.street) { draft = draft.copy(street = it) }
                ProfileField("Apartment / additional address", draft.extendedAddress) { draft = draft.copy(extendedAddress = it) }
                ProfileField("City", draft.city) { draft = draft.copy(city = it) }
                ProfileField("State / province", draft.region) { draft = draft.copy(region = it) }
                ProfileField("Country (two-letter code)", draft.country) { draft = draft.copy(country = it.uppercase()) }
                ProfileField("Postal code", draft.postalCode) { draft = draft.copy(postalCode = it) }
            }
            item {
                if (error != null && (draft != ShopProfile() || saved != null)) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = error == null && draft != saved,
                    onClick = {
                        viewModel.saveProfile(draft)
                        if (!required) messenger.show("Profile saved")
                    },
                ) { Text(if (required) "Continue" else "Save") }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
