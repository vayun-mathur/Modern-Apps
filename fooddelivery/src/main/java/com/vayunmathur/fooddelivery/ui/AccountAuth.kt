package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text

@Composable
internal fun AccountAuthCard(
    phone: String,
    onPhoneChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    codeSent: Boolean,
    authLoading: Boolean,
    onAuthClick: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            IconPerson(
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.sign_in_to_food_delivery),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.view_orders_earn_rewards_and_more),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = phone,
                onValueChange = onPhoneChange,
                label = { Text(stringResource(R.string.phone_number)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )

            if (codeSent) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text(stringResource(R.string.verification_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            // Busy is encoded by the disabled button, no spinner icon.
            Button(
                onClick = onAuthClick,
                enabled = !authLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (codeSent) "Verify" else "Send Code")
            }
        }
    }
}
