package com.vayunmathur.findfamily.ui.dialog

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
import androidx.compose.ui.window.Dialog
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.findfamily.Networking
import com.vayunmathur.findfamily.Platform
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.pop
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
fun AddLinkDialog(backStack: NavBackStack<Route>, viewModel: DatabaseViewModel, platform: Platform) {
    var name by remember { mutableStateOf("") }

    val options = mapOf(
        "15 minutes" to 15.minutes, "30 minutes" to 30.minutes, "1 hour" to 1.hours, "2 hours" to 2.hours, "4 hours" to 4.hours, "6 hours" to 6.hours, "12 hours" to 12.hours, "1 day" to 1.days, "2 days" to 2.days, "1 week" to 7.days
    )
    var expiryTime by remember { mutableStateOf("15 minutes") }

    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Share via link", style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(name, {name = it}, label = {Text("Link Label (to differentiate links)")})

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
                            viewModel.upsert(newLink)
                            backStack.pop()
                        }
                    },
                    enabled = name.isNotBlank()
                ) {
                    Text("Create Temporary Link")
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
            label = { Text("Expiry Time") },
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