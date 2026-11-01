package com.vayunmathur.globalesim.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.globalesim.data.api.ProfileDto
import com.vayunmathur.globalesim.domain.QuoteMath
import com.vayunmathur.globalesim.domain.buildActivationCode
import com.vayunmathur.globalesim.platform.EsimUiState
import com.vayunmathur.library.intents.euicc.EsimLink
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Purchase tab: your global eSIM at a glance — balance, top-up, install,
 * and Copy SIM ID for moving to another phone. Without an eSIM it shows
 * the setup terms, the first top-up, and Claim for an eSIM copied from
 * another phone. Pull down anywhere to refresh status and balance.
 *
 * Card top-ups use our own form ([CardPaymentSheet]) confirmed via Stripe's
 * [PaymentLauncher]; Google Pay goes through [GooglePayLauncher]. The
 * ViewModel still owns secret fetch + credit watch — this screen only
 * presents and confirms.
 */
@Composable
fun HomeScreen(
    state: EsimUiState,
    launchers: CardPaymentLaunchers? = null,
    onStartCardPurchase: (amountCents: Long, publishableKey: String?, onKey: (String) -> Unit) -> Unit,
    onStartCryptoPurchase: (Long) -> Unit,
    onClearCryptoInvoice: () -> Unit,
    onClaim: (simId: String) -> Unit,
    onRefresh: () -> Unit,
    /**
     * Presents the custom card sheet for a client secret. Hoisted so previews
     * can render without it: production passes the activity-created
     * [launchers]; previews pass neither and render launcher-free.
     */
    onPresentPaymentSheet: ((secret: String) -> Unit)? = null,
) {
    val messenger = rememberMessenger()
    val context = LocalContext.current
    // Amount chosen in TopUpCard; the host shows the sheet and confirms.
    var sheetAmountCents by rememberSaveable { mutableStateOf<Long?>(null) }
    CardPaymentHost(
        state = state,
        launchers = launchers,
        sheetAmountCents = sheetAmountCents,
        onDismissSheet = { sheetAmountCents = null },
        onSheetAmountConsumed = { sheetAmountCents = null },
        onStartCardPurchase = onStartCardPurchase,
        onPresentPaymentSheet = onPresentPaymentSheet,
    )
    AppScaffold(
        title = "Purchase",
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        AppPullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            topClearance = padding.calculateTopPadding(),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
            ) {
                val profile = state.profile
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        when {
                            !state.statusLoaded -> LoadingIndicator()
                            profile == null && state.pendingCreditCents > 0 -> {
                                Text("Payment received", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Creating your eSIM. This page updates on its own.",
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            profile == null -> SetupTerms()
                            else -> BalanceSummary(profile, state.balanceStale, state.pendingCreditCents)
                        }
                    }
                }
                if (state.statusLoaded) {
                    TopUpCard(
                        busy = state.loading || state.pendingClientSecret != null,
                        onBuyWithCard = { cents -> sheetAmountCents = cents },
                        onBuyWithCrypto = onStartCryptoPurchase,
                        pendingInvoiceUrl = state.pendingInvoiceUrl,
                        onClearCryptoInvoice = onClearCryptoInvoice,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state.statusLoaded && profile == null && state.pendingCreditCents == 0L) {
                    ClaimCard(
                        busy = state.loading,
                        onClaim = onClaim,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (profile != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                // The vendor stores SM-DP+ and matching ID separately;
                                // the LPA needs the full LPA:1$<smdp>$<code> string.
                                // A bare matching ID fails validation and lands on a
                                // blank manual-entry page. The server sometimes fuses
                                // smdp+code with the `$` missing, so normalize first.
                                val full = buildActivationCode(profile.activationCode, profile.smdpAddress)
                                val intent = Intent(Intent.ACTION_VIEW, EsimLink.provisioningUri(full))
                                    .setPackage(EsimLink.LPA_PACKAGE)
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    messenger.show("This phone doesn't have the eSIM installer.")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Install eSIM")
                        }
                        OutlinedButton(
                            onClick = {
                                state.simId?.let {
                                    copySimId(context, it)
                                    messenger.show("SIM ID copied. Paste it into Claim on your new phone.")
                                }
                            },
                            enabled = state.simId != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Copy SIM ID")
                        }
                    }
                }
            }
        }
    }
}

/** Setup terms for a phone with no eSIM yet. The $3 fee gets its own line. */
@Composable
private fun SetupTerms() {
    Text("One global eSIM for 200+ countries", style = MaterialTheme.typography.titleMedium)
    Text(
        "Pay as you go, credit never expires. No email, phone number or KYC.",
        modifier = Modifier.padding(top = 4.dp),
    )
    Text(
        "$3 one-time setup fee",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Taken from your first top-up ($5 minimum). A $5 top-up leaves $2 of credit.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun BalanceSummary(profile: ProfileDto, stale: Boolean, pendingCreditCents: Long) {
    // A stale read with no history is a placeholder zero, not a real balance.
    val unknown = stale && profile.status == "unknown" && profile.walletUsdCents == 0L
    Text(
        if (unknown) "—" else QuoteMath.formatCents(profile.walletUsdCents),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(
        "Balance · ${statusLabel(profile.status)}" + if (stale && !unknown) " · last known" else "",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (pendingCreditCents > 0) {
        Text(
            "${QuoteMath.formatCents(pendingCreditCents)} on its way. No need to pay again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun statusLabel(status: String): String = when (status) {
    "pending" -> "Activating"
    "active" -> "Active"
    "suspended" -> "Paused"
    "terminated" -> "Terminated"
    "unknown" -> "Checking…"
    else -> status.replaceFirstChar { it.uppercase() }
}

/** The SIM ID is the account's only credential: flag the clip as sensitive so it isn't previewed. */
@SuppressLint("InlinedApi") // Before Android 13 the extra is ignored; the copy still works.
private fun copySimId(context: Context, simId: String) {
    val clip = ClipData.newPlainText("SIM ID", simId).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}
