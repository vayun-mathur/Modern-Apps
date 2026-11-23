package com.vayunmathur.taxi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.taxi.data.AddCardResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.PaymentActionResult
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(onConnectLyft: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LyftProvider(context.applicationContext) }

    var lyftSignedIn by remember { mutableStateOf(false) }
    var loadingPayments by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<ChargeAccount>>(emptyList()) }
    var paymentError by remember { mutableStateOf<String?>(null) }
    // The id of the account a live set-default/remove is currently running against.
    var busyId by remember { mutableStateOf<String?>(null) }
    // Add-card dialog state.
    var showAddCard by remember { mutableStateOf(false) }
    var addingCard by remember { mutableStateOf(false) }
    var addCardError by remember { mutableStateOf<String?>(null) }
    var showSignOut by remember { mutableStateOf(false) }
    val addCardUnsupportedMessage = stringResource(R.string.add_card_unsupported)
    var signingOut by remember { mutableStateOf(false) }

    suspend fun loadPayments() {
        loadingPayments = true
        paymentError = null
        when (val result = provider.paymentMethods()) {
            is PaymentMethodsResult.Success -> accounts = result.accounts
            is PaymentMethodsResult.NotSignedIn -> {
                accounts = emptyList()
                lyftSignedIn = false
            }
            is PaymentMethodsResult.Failed -> paymentError = result.message
            PaymentMethodsResult.Unsupported -> paymentError = null
        }
        loadingPayments = false
    }

    LaunchedEffect(Unit) {
        lyftSignedIn = LyftTokenStore(context.applicationContext).isSignedIn()
        if (lyftSignedIn) loadPayments()
    }

    suspend fun applyAction(result: PaymentActionResult, onOk: suspend () -> Unit) {
        when (result) {
            is PaymentActionResult.Success ->
                if (result.accounts != null) accounts = result.accounts else onOk()
            is PaymentActionResult.Failed -> paymentError = result.message
            PaymentActionResult.Unsupported -> Unit
        }
        busyId = null
    }

    AppScaffold(title = stringResource(UiR.string.settings)) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.provider_lyft)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (lyftSignedIn) R.string.signed_in else R.string.not_signed_in,
                            ),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onConnectLyft),
                )
            }

            if (lyftSignedIn) {
                TextButton(
                    onClick = { showSignOut = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            }

            Text(
                stringResource(R.string.payment_methods),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !lyftSignedIn -> InfoCard(stringResource(R.string.payment_sign_in))
                loadingPayments -> LoadingCard(stringResource(R.string.payment_methods_loading))
                paymentError != null -> InfoCard(
                    stringResource(R.string.payment_methods_error, paymentError.orEmpty()),
                )
                accounts.isEmpty() -> InfoCard(stringResource(R.string.payment_methods_none))
                else -> Card {
                    accounts.forEachIndexed { index, account ->
                        PaymentRow(
                            account = account,
                            busy = busyId == account.id,
                            onSetDefault = {
                                busyId = account.id
                                scope.launch {
                                    applyAction(provider.setDefaultPaymentMethod(account.id)) {
                                        loadPayments()
                                    }
                                }
                            },
                            onRemove = {
                                busyId = account.id
                                scope.launch {
                                    applyAction(provider.removePaymentMethod(account.id)) {
                                        loadPayments()
                                    }
                                }
                            },
                        )
                        if (index < accounts.lastIndex) HorizontalDivider()
                    }
                }
            }

            if (lyftSignedIn && !loadingPayments) {
                Button(
                    onClick = {
                        addCardError = null
                        showAddCard = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.add_card))
                }
            }
        }
    }

    if (showSignOut) {
        AlertDialog(
            onDismissRequest = { if (!signingOut) showSignOut = false },
            title = { Text(stringResource(R.string.sign_out_title)) },
            text = { Text(stringResource(R.string.sign_out_message)) },
            confirmButton = {
                TextButton(
                    enabled = !signingOut,
                    onClick = {
                        signingOut = true
                        scope.launch {
                            provider.signOut()
                            signingOut = false
                            showSignOut = false
                            lyftSignedIn = false
                            accounts = emptyList()
                            paymentError = null
                        }
                    },
                ) {
                    Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(enabled = !signingOut, onClick = { showSignOut = false }) {
                    Text(stringResource(UiR.string.cancel))
                }
            },
        )
    }

    if (showAddCard) {
        AddCardDialog(
            adding = addingCard,
            error = addCardError,
            onDismiss = {
                if (!addingCard) {
                    showAddCard = false
                    addCardError = null
                }
            },
            onSubmit = { card, makeDefault ->
                addingCard = true
                addCardError = null
                scope.launch {
                    when (val result = provider.addCard(card, makeDefault)) {
                        is AddCardResult.Success -> {
                            if (result.accounts != null) accounts = result.accounts else loadPayments()
                            addingCard = false
                            showAddCard = false
                        }
                        is AddCardResult.Failed -> {
                            addCardError = result.message
                            addingCard = false
                        }
                        AddCardResult.Unsupported -> {
                            addCardError = addCardUnsupportedMessage
                            addingCard = false
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun PaymentRow(
    account: ChargeAccount,
    busy: Boolean,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(account.label, style = MaterialTheme.typography.bodyLarge)
            if (account.isDefault) {
                Text(
                    stringResource(R.string.payment_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (!busy) {
            if (!account.isDefault) {
                TextButton(onClick = onSetDefault) {
                    Text(stringResource(R.string.card_set_default))
                }
            }
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(UiR.string.remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InfoCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(
            message,
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingCard(message: String) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
