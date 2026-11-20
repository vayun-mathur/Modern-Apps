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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.shop.R
import com.vayunmathur.shop.data.ShopProfile
import com.vayunmathur.shop.platform.ShopViewModel

/** Draft edits survive tab switches and process recreation, so the draft is saveable state. */
private val ShopProfileSaver = listSaver<ShopProfile, String>(
    save = {
        listOf(
            it.firstName, it.lastName, it.email, it.phone, it.street, it.extendedAddress,
            it.city, it.region, it.country, it.postalCode,
        )
    },
    restore = {
        ShopProfile(
            firstName = it[0], lastName = it[1], email = it[2], phone = it[3], street = it[4],
            extendedAddress = it[5], city = it[6], region = it[7], country = it[8], postalCode = it[9],
        )
    },
)

/** Buyer profile. With [required] it is the first-run gate and the rest of the app stays locked until saved. */
@Composable
fun ProfileScreen(viewModel: ShopViewModel, required: Boolean) {
    val messenger = rememberMessenger()
    val saved = viewModel.profile
    var draft by rememberSaveable(saved, stateSaver = ShopProfileSaver) { mutableStateOf(saved ?: ShopProfile()) }
    val error = draft.validationError()
    // Captured at composition: the saved confirmation fires from a click callback.
    val profileSavedMessage = stringResource(R.string.profile_saved)
    AppScaffold(title = stringResource(R.string.profile_title)) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (required) {
                    Text(stringResource(R.string.profile_add_details), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    stringResource(R.string.profile_explainer),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                ProfileField(stringResource(R.string.first_name), draft.firstName) { draft = draft.copy(firstName = it) }
                ProfileField(stringResource(R.string.last_name), draft.lastName) { draft = draft.copy(lastName = it) }
                ProfileField(stringResource(UiR.string.email), draft.email, KeyboardType.Email) { draft = draft.copy(email = it) }
                ProfileField(stringResource(R.string.phone_international), draft.phone, KeyboardType.Phone) {
                    draft = draft.copy(phone = it)
                }
            }
            item {
                Text(stringResource(R.string.delivery_address_optional), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.delivery_address_hint), style = MaterialTheme.typography.bodySmall)
                ProfileField(stringResource(R.string.street_address), draft.street) { draft = draft.copy(street = it) }
                ProfileField(stringResource(R.string.apartment_address), draft.extendedAddress) {
                    draft = draft.copy(extendedAddress = it)
                }
                ProfileField(stringResource(R.string.city), draft.city) { draft = draft.copy(city = it) }
                ProfileField(stringResource(R.string.state_province), draft.region) { draft = draft.copy(region = it) }
                ProfileField(stringResource(R.string.country_code), draft.country) {
                    draft = draft.copy(country = it.uppercase())
                }
                ProfileField(stringResource(R.string.postal_code), draft.postalCode) { draft = draft.copy(postalCode = it) }
            }
            item {
                if (error != null && (draft != ShopProfile() || saved != null)) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = error == null && draft != saved,
                    onClick = {
                        viewModel.saveProfile(draft)
                        if (!required) messenger.show(profileSavedMessage)
                    },
                ) {
                    Text(
                        if (required) stringResource(UiR.string.continue_label) else stringResource(UiR.string.save),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
