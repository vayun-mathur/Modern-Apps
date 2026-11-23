package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.R as UiR

@Composable
internal fun AddressFormCard(
    form: AddressFormState,
    onFormChange: (AddressFormState) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                if (form.editing != null) "Edit Address" else "Add Address",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = form.label,
                onValueChange = { onFormChange(form.copy(label = it)) },
                label = { Text(stringResource(R.string.label_e_g_home_work)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.street,
                onValueChange = { onFormChange(form.copy(street = it, error = null)) },
                label = { Text(stringResource(R.string.street_address)) },
                isError = form.triedSubmit && form.street.isBlank(),
                supportingText = if (form.triedSubmit && form.street.isBlank()) {
                    { Text(stringResource(R.string.required)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PostalAddress),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.city,
                    onValueChange = { onFormChange(form.copy(city = it, error = null)) },
                    label = { Text(stringResource(R.string.city)) },
                    isError = form.triedSubmit && form.city.isBlank(),
                    supportingText = if (form.triedSubmit && form.city.isBlank()) {
                        { Text(stringResource(R.string.required)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PostalAddress),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.state,
                    onValueChange = { onFormChange(form.copy(state = it, error = null)) },
                    label = { Text(stringResource(R.string.state)) },
                    isError = form.triedSubmit && form.state.isBlank(),
                    supportingText = if (form.triedSubmit && form.state.isBlank()) {
                        { Text(stringResource(R.string.required)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PostalAddress),
                    modifier = Modifier.width(80.dp)
                )
            }
            OutlinedTextField(
                value = form.zip,
                onValueChange = { onFormChange(form.copy(zip = it, error = null)) },
                label = { Text(stringResource(R.string.zip_code)) },
                isError = form.triedSubmit && form.zip.isBlank(),
                supportingText = if (form.triedSubmit && form.zip.isBlank()) {
                    { Text(stringResource(R.string.required)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.aptUnit,
                    onValueChange = { onFormChange(form.copy(aptUnit = it)) },
                    label = { Text(stringResource(R.string.apt_unit)) },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.gateCode,
                    onValueChange = { onFormChange(form.copy(gateCode = it)) },
                    label = { Text(stringResource(R.string.gate_code)) },
                    modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = form.instructions,
                onValueChange = { onFormChange(form.copy(instructions = it)) },
                label = { Text(stringResource(R.string.delivery_instructions_optional)) },
                modifier = Modifier.fillMaxWidth()
            )
            if (form.error != null) {
                Text(
                    form.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(UiR.string.cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = !form.saving,
                    modifier = Modifier.weight(1f)
                ) {
                    // Busy is encoded by the disabled button (enabled = !form.saving), no spinner icon.
                    Text(stringResource(UiR.string.save))
                }
            }
        }
    }
}
