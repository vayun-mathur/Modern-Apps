package com.vayunmathur.taxi.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.taxi.data.BookingResult
import com.vayunmathur.taxi.data.ChargeAccount
import com.vayunmathur.taxi.data.PaymentMethodsResult
import com.vayunmathur.taxi.data.Place
import com.vayunmathur.taxi.data.RideQuote
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import com.vayunmathur.taxi.data.lyft.PendingRideOffer
import com.vayunmathur.taxi.network.lyft.LyftProvider
import com.vayunmathur.taxi.notifications.RideTrackingService
import kotlinx.coroutines.launch

/** Everything the Lyft booking flow needs, gathered when the user taps a Lyft fare. */
data class LyftBookingRequest(
    val quote: RideQuote,
    val pickup: Place,
    val dropoff: Place,
    val purchaseSessionId: String?,
)

private sealed interface Step {
    data object LoadingPayments : Step

    data class PickPayment(val accounts: List<ChargeAccount>, val selectedId: String?) : Step

    data class Confirm(val account: ChargeAccount?) : Step

    data object Booking : Step

    data class DryRun(val requestJson: String) : Step

    data class Created(val rideId: String?, val status: String?, val raw: String) : Step

    data class Failed(val message: String) : Step
}

/**
 * The in-app Lyft booking flow: pick a card, confirm, then either show the dry-run request (the
 * default, no charge) or the created ride's status. Rendered as a stack of dialogs so it overlays
 * whatever screen launched it; [onDismiss] tears the whole flow down.
 */
@Composable
fun LyftBookingFlow(
    request: LyftBookingRequest,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = remember { LyftProvider(context.applicationContext) }
    var step by remember { mutableStateOf<Step>(Step.LoadingPayments) }
    // Contextual notification ask (todo/files pattern): only when a booking is
    // confirmed and ride tracking starts, ask once and persist that we asked.
    val notificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(step) {
        if (step is Step.Created && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = context.getSharedPreferences("taxi_notifications", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("has_asked_notifications", false)) {
                prefs.edit().putBoolean("has_asked_notifications", true).apply()
                if (ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
    val noPaymentMessage = stringResource(R.string.booking_no_payment)
    val connectPromptMessage = stringResource(R.string.connect_prompt, "Lyft")

    LaunchedEffect(request) {
        step = when (val result = provider.paymentMethods()) {
            is PaymentMethodsResult.Success ->
                if (result.accounts.isEmpty()) {
                    Step.Failed(noPaymentMessage)
                } else {
                    val default = result.accounts.firstOrNull { it.isDefault } ?: result.accounts.first()
                    Step.PickPayment(result.accounts, default.id)
                }
            is PaymentMethodsResult.NotSignedIn ->
                Step.Failed(connectPromptMessage)
            is PaymentMethodsResult.Failed -> Step.Failed(result.message)
            PaymentMethodsResult.Unsupported ->
                Step.Failed(noPaymentMessage)
        }
    }

    fun book(account: ChargeAccount?) {
        step = Step.Booking
        scope.launch {
            // Persist the confirmed offer BEFORE the create call: if the process
            // dies while the request is in flight, the next launch reconciles this
            // against activeRide() instead of booking (and charging) a second time.
            val tokenStore = LyftTokenStore(context.applicationContext)
            if (request.quote.offerId != null) {
                tokenStore.savePendingRide(
                    PendingRideOffer.from(
                        quote = request.quote,
                        pickup = request.pickup,
                        dropoff = request.dropoff,
                        chargeAccountId = account?.id,
                        purchaseSessionId = request.purchaseSessionId,
                        savedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            step = when (
                val result = provider.createRide(
                    quote = request.quote,
                    pickup = request.pickup,
                    dropoff = request.dropoff,
                    account = account,
                    purchaseSessionId = request.purchaseSessionId,
                    dryRun = false, // createRide enforces dry-run while BOOKING_LIVE is false
                )
            ) {
                is BookingResult.DryRun -> {
                    // Nothing was sent, so nothing is in flight.
                    tokenStore.clearPendingRide()
                    Step.DryRun(result.requestJson)
                }
                is BookingResult.Created -> {
                    // The ride now exists server-side and is tracked by id; the
                    // pending offer has served its purpose.
                    tokenStore.clearPendingRide()
                    // Kick off the background Live Update tracker for the new ride.
                    RideTrackingService.start(context, result.rideId)
                    Step.Created(result.rideId, result.status, result.raw)
                }
                // Failed keeps the pending offer: a transport failure may still have
                // created the ride server-side, so launch reconciliation (activeRide)
                // decides — never an automatic re-book.
                is BookingResult.Failed -> Step.Failed(result.message)
                BookingResult.Unsupported ->
                    Step.Failed(noPaymentMessage)
            }
        }
    }

    when (val current = step) {
        Step.LoadingPayments -> ProgressDialog(stringResource(R.string.booking), onDismiss)

        is Step.PickPayment -> LyftPaymentPickerDialog(
            accounts = current.accounts,
            selectedId = current.selectedId,
            onSelect = { step = current.copy(selectedId = it) },
            onConfirm = {
                val account = current.accounts.firstOrNull { it.id == current.selectedId }
                step = Step.Confirm(account)
            },
            onDismiss = onDismiss,
        )

        is Step.Confirm -> LyftConfirmBookingDialog(
            quote = request.quote,
            account = current.account,
            onConfirm = { book(current.account) },
            onDismiss = onDismiss,
        )

        Step.Booking -> ProgressDialog(stringResource(R.string.booking), onDismiss = {})

        is Step.DryRun -> DryRunDialog(current.requestJson, onDismiss)

        is Step.Created -> MessageDialog(
            title = stringResource(R.string.ride_created, current.rideId?.let { " ($it)" } ?: ""),
            onDismiss = onDismiss,
        )

        is Step.Failed -> MessageDialog(
            title = stringResource(R.string.booking_failed, current.message),
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun DryRunDialog(requestJson: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.booking_dry_run_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.booking_dry_run_done),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        requestJson,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) } },
    )
}

@Composable
private fun ProgressDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(message) },
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loading\u2026")
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun MessageDialog(title: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.close)) } },
    )
}
