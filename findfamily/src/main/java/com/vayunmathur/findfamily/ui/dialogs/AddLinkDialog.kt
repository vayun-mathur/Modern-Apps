package com.vayunmathur.findfamily.ui.dialogs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.findfamily.util.Networking
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.TemporaryLink
import dev.whyoleg.cryptography.algorithms.RSA
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Composable
fun AddLinkDialog(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel) {
    var name by remember { mutableStateOf("") }

    val expiry15min = stringResource(R.string.expiry_15_minutes)
    val options = mapOf(
        expiry15min to 15.minutes,
        stringResource(R.string.expiry_30_minutes) to 30.minutes,
        stringResource(R.string.expiry_1_hour) to 1.hours,
        stringResource(R.string.expiry_2_hours) to 2.hours,
        stringResource(R.string.expiry_4_hours) to 4.hours,
        stringResource(R.string.expiry_6_hours) to 6.hours,
        stringResource(R.string.expiry_12_hours) to 12.hours,
        stringResource(R.string.expiry_1_day) to 1.days,
        stringResource(R.string.expiry_2_days) to 2.days,
        stringResource(R.string.expiry_1_week) to 7.days
    )
    var expiryTime by remember { mutableStateOf(expiry15min) }

    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.add_link_title), style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(name, {name = it}, label = {Text(stringResource(R.string.add_link_label))})

                DropdownField(expiryTime, { expiryTime = it }, options.keys)

                Button(
                    {
                        CoroutineScope(Dispatchers.IO).launch {
                            val keypair = Networking.generateKeyPair()

                            val newLink = TemporaryLink(
                                name,
                                Base64.encode(keypair.privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM)),
                                Base64.encode(keypair.publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM)),
                                Clock.System.now() + options[expiryTime]!!
                            )
                            viewModel.upsertAsync(newLink)
                            backStack.pop()
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text(stringResource(R.string.create_temporary_link))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(value: String, setValue: (String) -> Unit, options: Collection<String>) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value, {},
            interactionSource = interactionSourceClickable { expanded = true },
            readOnly = true,
            label = { Text(stringResource(R.string.expiry_time_label)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = expanded
                )
            },
        )
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { selectionOption ->
                DropdownMenuItem({Text(text = selectionOption)}, {
                    setValue(selectionOption)
                    expanded = false
                })
            }
        }
    }
}