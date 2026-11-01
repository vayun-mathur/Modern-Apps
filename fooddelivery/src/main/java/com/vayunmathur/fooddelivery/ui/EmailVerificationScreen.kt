package com.vayunmathur.fooddelivery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.fooddelivery.R
import com.vayunmathur.fooddelivery.api.BitesCustomers
import com.vayunmathur.fooddelivery.platform.AppInit
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconWarning
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior

private enum class VerifyState { LOADING, SUCCESS, ERROR }

/** Opened from an email-verification link; posts the link's customer id + token. */
@Composable
internal fun EmailVerificationScreen(
    customerId: String,
    token: String,
    onDone: () -> Unit,
) {
    var state by remember { mutableStateOf(VerifyState.LOADING) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(customerId, token, attempt) {
        state = VerifyState.LOADING
        AppInit.awaitReady()
        state = if (BitesCustomers.verifyEmailToken(customerId, token)) {
            VerifyState.SUCCESS
        } else {
            VerifyState.ERROR
        }
    }

    EmailVerificationContent(
        state = state,
        onRetry = { attempt++ },
        onDone = onDone,
    )
}

@Composable
private fun EmailVerificationContent(
    state: VerifyState,
    onRetry: () -> Unit,
    onDone: () -> Unit,
) {
    AppScaffold(
        title = stringResource(R.string.email_verification),
        onClose = onDone,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state) {
                VerifyState.LOADING -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.verifying_your_email))
                }
                VerifyState.SUCCESS -> {
                    IconCheck(
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.email_verified),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onDone) { Text(stringResource(UiR.string.done)) }
                }
                VerifyState.ERROR -> {
                    IconWarning(
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.email_verification_failed),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.email_verification_failed_detail),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = onRetry) { Text(stringResource(UiR.string.retry)) }
                    TextButton(onClick = onDone) { Text(stringResource(UiR.string.close)) }
                }
            }
        }
    }
}
