package com.vayunmathur.messages.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.messages.R
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.rcs.RcsClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RcsLoginScreen(backStack: NavBackStack<Route>) {
    val state by RcsClient.state.collectAsState()
    var phoneNumber by remember { mutableStateOf("") }
    var otpCode by remember { mutableStateOf("") }

    LaunchedEffect(state) {
        if (state is RcsClient.State.Connected) backStack.pop()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.setup_rcs_title)) },
                navigationIcon = { IconNavigation(backStack) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is RcsClient.State.Idle,
                is RcsClient.State.NeedsSetup -> {
                    Text(
                        stringResource(R.string.setup_rcs_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone number (E.164, e.g. +1234567890)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { RcsClient.startProvisioning(phoneNumber.ifBlank { null }) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = phoneNumber.isNotBlank(),
                    ) {
                        Text(
                            stringResource(R.string.setup_rcs_start),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Note: RCS protocol implementation is in progress. " +
                            "This will attempt ACS provisioning per GSMA RCC.14, " +
                            "then SIP registration, then Tachyon gRPC connection to Google Jibe. " +
                            "See reverse-engineering findings for technical details.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is RcsClient.State.Provisioning -> {
                    Text(
                        "Provisioning RCS...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.height(40.dp),
                        strokeWidth = 3.dp,
                    )
                    Text(
                        "Contacting ACS server to verify phone number and retrieve SIP configuration.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is RcsClient.State.AwaitingOtp -> {
                    Text(
                        "Enter verification code",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "We sent an SMS with a verification code to verify your phone number for RCS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { otpCode = it },
                        label = { Text("Verification code") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(4.dp))
                    Button(
                        onClick = { RcsClient.submitOtp(otpCode) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                        enabled = otpCode.isNotBlank(),
                    ) {
                        Text("Verify", fontWeight = FontWeight.SemiBold)
                    }
                }

                is RcsClient.State.Connecting -> {
                    Text(
                        "Connecting to RCS...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    CircularProgressIndicator(
                        modifier = Modifier.height(40.dp),
                        strokeWidth = 3.dp,
                    )
                }

                is RcsClient.State.Connected -> {
                    Text("Connected to RCS!")
                }

                is RcsClient.State.Disconnected -> {
                    Text(
                        "Disconnected: ${s.reason}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { RcsClient.start() },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 14.dp),
                    ) {
                        Text("Retry", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
