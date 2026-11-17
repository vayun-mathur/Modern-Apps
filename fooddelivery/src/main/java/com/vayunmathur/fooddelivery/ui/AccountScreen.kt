@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.fooddelivery.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.fooddelivery.network.BitesAuth
import com.vayunmathur.fooddelivery.network.BitesCustomers
import com.vayunmathur.fooddelivery.network.BitesRewards
import com.vayunmathur.fooddelivery.data.AddressStore
import com.vayunmathur.fooddelivery.data.Customer
import com.vayunmathur.fooddelivery.data.CustomerSavings
import com.vayunmathur.fooddelivery.data.PlatformSavings
import com.vayunmathur.fooddelivery.data.Referral
import com.vayunmathur.fooddelivery.data.SavedAddress
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private const val PREFS_NAME = "fooddelivery_prefs"
private const val KEY_TOKEN = "token_json"

@Composable
fun AccountScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var customer by remember { mutableStateOf<Customer?>(null) }
    var savings by remember { mutableStateOf<CustomerSavings?>(null) }
    var referrals by remember { mutableStateOf<List<Referral>>(emptyList()) }
    var platformSavings by remember { mutableStateOf<PlatformSavings?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var loggedIn by remember { mutableStateOf(BitesAuth.isLoggedIn()) }
    // The saved token is restored by the background warm-up, so "signed out" isn't known to
    // be true until that has landed — don't offer the sign-in card before then.
    var authResolved by remember { mutableStateOf(BitesAuth.isLoggedIn()) }

    var stateId by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var authLoading by remember { mutableStateOf(false) }

    var addresses by remember { mutableStateOf<List<SavedAddress>>(emptyList()) }
    var addressesLoaded by remember { mutableStateOf(false) }
    var addressForm by remember { mutableStateOf(AddressFormState()) }

    fun resetAddressForm() {
        addressForm = AddressFormState()
    }

    LaunchedEffect(Unit) {
        addresses = AddressStore.getAll(context)
        addressesLoaded = true
    }

    // The saved token is restored by the background warm-up, so re-read the login state once
    // it has landed instead of assuming the initial (possibly pre-restore) answer.
    LaunchedEffect(Unit) {
        AppInit.awaitReady()
        if (!loggedIn) loggedIn = BitesAuth.isLoggedIn()
        authResolved = true
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) {
            customer = BitesCustomers.getCustomer()
            savings = BitesCustomers.getCustomerSavings()
            referrals = BitesCustomers.getReferrals()
            platformSavings = BitesRewards.getPlatformSavings()
        }
    }

    AppScaffold(topBar = {}) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!loggedIn && authResolved) {
                AccountAuthCard(
                    phone = phone,
                    onPhoneChange = { phone = it },
                    code = code,
                    onCodeChange = { code = it },
                    codeSent = codeSent,
                    authLoading = authLoading,
                    onAuthClick = {
                        scope.launch {
                            authLoading = true
                            if (!codeSent) {
                                val sid = BitesAuth.verifyPhone(phone)
                                if (sid != null) {
                                    stateId = sid
                                    codeSent = true
                                }
                            } else {
                                val sid = stateId ?: return@launch
                                val token = BitesAuth.exchangeOtpCodeForToken(sid, code)
                                if (token != null && token.accessToken.isNotEmpty()) {
                                    BitesAuth.setToken(token)
                                    loggedIn = true
                                }
                            }
                            authLoading = false
                        }
                    },
                )
            } else if (loggedIn) {
                if (editingProfile) {
                    customer?.let { c ->
                        EditProfileDialog(
                            customer = c,
                            onDismiss = { editingProfile = false },
                            onSave = { updated ->
                                scope.launch {
                                    editingProfile = false
                                    val saved = BitesCustomers.createOrUpdateCustomer(updated)
                                    if (saved != null) {
                                        customer = saved
                                        notice = "Profile updated"
                                    } else {
                                        notice = "Couldn't update your profile"
                                    }
                                }
                            },
                        )
                    }
                }

                AccountProfileCard(customer = customer, onEdit = { editingProfile = true })
                AccountSavingsCard(savings = savings)

                // Email verification — the account's email is unverified until the emailed
                // token is confirmed, so offer to (re)send it.
                customer?.let { c ->
                    if (c.email.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.email_verification),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val ok = BitesCustomers.sendEmailVerification(c.email)
                                            notice = if (ok) "Verification email sent to ${c.email}"
                                            else "Couldn't send the verification email"
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.send_verification_email)) }
                            }
                        }
                    }
                }

                AccountReferralsCard(referrals = referrals, platformSavings = platformSavings)

                notice?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text(stringResource(R.string.delete_account)) },
                        text = { Text(stringResource(R.string.delete_account_warning)) },
                        confirmButton = {
                            Button(onClick = {
                                scope.launch {
                                    confirmDelete = false
                                    if (BitesCustomers.deleteCustomer()) {
                                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                            .edit { remove(KEY_TOKEN) }
                                        BitesAuth.clearToken()
                                        loggedIn = false
                                        customer = null
                                        savings = null
                                    } else {
                                        notice = "Couldn't delete the account"
                                    }
                                }
                            }) { Text(stringResource(R.string.delete_account)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) {
                                Text(stringResource(UiR.string.cancel))
                            }
                        },
                    )
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = {
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit { remove(KEY_TOKEN) }
                        BitesAuth.clearToken()
                        loggedIn = false
                        customer = null
                        savings = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.sign_out))
                }

                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.delete_account),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.addresses),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = {
                    resetAddressForm()
                    addressForm = addressForm.copy(showing = true)
                }) {
                    IconAdd(tint = MaterialTheme.colorScheme.primary)
                }
            }

            AccountAddressesSection(
                scope = scope,
                context = context,
                addressesLoaded = addressesLoaded,
                addresses = addresses,
                onAddressesChange = { addresses = it },
                form = addressForm,
                onFormChange = { addressForm = it },
                onResetForm = { resetAddressForm() },
            )
        }
    }
}
