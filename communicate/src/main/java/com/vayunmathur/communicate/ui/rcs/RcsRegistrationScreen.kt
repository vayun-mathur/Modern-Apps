package com.vayunmathur.communicate.ui.rcs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsFeature
import com.vayunmathur.communicate.data.rcs.RcsMsrpListen
import com.vayunmathur.communicate.data.rcs.RcsMsrpTls
import com.vayunmathur.communicate.data.rcs.RcsProvisioning
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsSipTransport
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RCS status / provisioning / probe UI.
 *
 * Shows the transport lifecycle (`Provisioning / Available / Unavailable +
 * reason + Retry`) plus a probe button that runs provisioning + UCE
 * self-check without sending. Single public `@Composable`, like the
 * registration screens.
 */
@Composable
fun RcsRegistrationScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val rcsAvailableMessage = stringResource(R.string.rcs_available)
    val rcsNotAvailableMessage = stringResource(R.string.rcs_not_available)
    val scope = rememberCoroutineScope()
    val transportState by RcsSipTransport.state.collectAsState()
    val provisioningState by RcsProvisioning.state.collectAsState()
    // Probe runs: bumped to show a one-shot result line.
    var probeRuns by remember { mutableIntStateOf(0) }
    var probing by remember { androidx.compose.runtime.mutableStateOf(false) }

    DetailScaffold(
        title = stringResource(R.string.rcs_status_title),
        onNavigateBack = onBack,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                ListItem(
                    content = {
                        Text("RCS", fontWeight = FontWeight.SemiBold)
                    },
                    supportingContent = {
                        Text(statusSubtitle(transportState, provisioningState))
                    },
                )
                if (transportState is RcsRegistrationState.Available) {
                    ListItem(
                        content = {
                            Text("Transport", fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text(RcsSipTransport.activeLeg)
                        },
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                RcsProvisioning.probe(context)
                                RcsSipTransport.ensureRegistered(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rcs_retry))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                probing = true
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val url = RcsProvisioning.lastConfigServerUrl
                                        val httpOk = url != null &&
                                            RcsProvisioning.entitlementCheck(url)
                                        httpOk || RcsCapabilityExchange.uceAvailable
                                    }.getOrDefault(false)
                                }
                                probing = false
                                probeRuns++
                                AppMessages.show(
                                    if (ok) rcsAvailableMessage
                                    else rcsNotAvailableMessage,
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rcs_probe))
                    }
                }
            }
        }

        if (probing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.width(20.dp).height(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rcs_provisioning))
            }
        }

        // Last-known carrier config URL (null = never probed).
        val lastUrl = RcsProvisioning.lastConfigServerUrl
        if (lastUrl != null || probeRuns > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (lastUrl.isNullOrBlank()) {
                    "No carrier RCS config URL (common on MVNO/Jibe carriers — delegate creation attempted anyway)"
                } else {
                    "Config server: $lastUrl"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Media transport diagnostics (§6.2): IMS-PDN listen state + TLS
        // identity fingerprint for manual verification.
        val mediaState by produceState<String?>(initialValue = null, transportState, probeRuns) {
            value = withContext(Dispatchers.IO) {
                if (!RcsFeature.enabled) return@withContext null
                val listen = if (RcsMsrpListen.isListening()) {
                    "listening ${RcsMsrpListen.listenIp()}:${RcsMsrpListen.listenPort()}"
                } else {
                    "not listening (active-only)"
                }
                val tls = runCatching {
                    RcsMsrpTls.ensureIdentity(context)?.fingerprint
                }.getOrNull()
                "MSRP: $listen\nTLS identity: ${tls ?: "unavailable (plaintext only)"}"
            }
        }
        if (mediaState != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                mediaState!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        // Back navigation is the scaffold's navigate-back affordance (onNavigateBack above).
    }
}

private fun statusSubtitle(
    transport: RcsRegistrationState,
    provisioning: RcsRegistrationState,
): String {
    val primary = transport.takeUnless { it is RcsRegistrationState.Unknown } ?: provisioning
    return when (primary) {
        is RcsRegistrationState.Available -> "Available"
        is RcsRegistrationState.Provisioning -> "Checking availability…"
        is RcsRegistrationState.Disabled -> "Disabled in this build"
        is RcsRegistrationState.Unknown -> "Not checked yet"
        is RcsRegistrationState.Unavailable -> "Unavailable: ${reasonText(primary.reason)}"
    }
}

private fun reasonText(reason: RcsUnavailableReason): String = when (reason) {
    RcsUnavailableReason.NoEntitlementUrl -> "carrier did not provision RCS (no config URL)"
    RcsUnavailableReason.NoCarrierPrivilege -> "carrier blocked IMS access (no carrier privilege for this SIM)"
    RcsUnavailableReason.NotSupported -> "device does not support single registration"
    RcsUnavailableReason.NoSubscription -> "no active subscription"
    RcsUnavailableReason.ProvisioningRequired -> "carrier provisioning required"
    RcsUnavailableReason.UceDisabled -> "capability exchange disabled"
    RcsUnavailableReason.TransportDenied -> "carrier denied the RCS feature tags"
    RcsUnavailableReason.ServiceUnavailable -> "IMS service unavailable"
    RcsUnavailableReason.Unknown -> "unknown error"
}
