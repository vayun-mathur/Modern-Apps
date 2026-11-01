package com.vayunmathur.travel.ui
import com.vayunmathur.travel.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.ElevatedCard
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconCheckCircle
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.DateString
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.travel.Route
import com.vayunmathur.travel.network.StayGuestInputDto
import com.vayunmathur.travel.platform.CardPaymentResult
import com.vayunmathur.travel.platform.rememberCardPayment
import com.vayunmathur.travel.util.StayBookingState
import com.vayunmathur.travel.util.TravelViewModel
import com.vayunmathur.travel.util.bookStay
import com.vayunmathur.travel.util.confirmPaidTrip
import com.vayunmathur.travel.util.resetStayBooking
import com.vayunmathur.travel.util.stayTotal
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StayGuestsPage(
    backStack: NavBackStack<Route>,
    viewModel: TravelViewModel,
) {
    val booking by viewModel.stayBooking.collectAsStateWithLifecycle()
    var paying by remember { mutableStateOf(false) }
    var paidTripId by remember { mutableStateOf<String?>(null) }
    val cardPayment = rememberCardPayment { result ->
        paying = false
        val tripId = paidTripId ?: return@rememberCardPayment
        when (result) {
            CardPaymentResult.Completed -> {
                viewModel.confirmPaidTrip(tripId)
                backStack.reset(Route.Home, Route.StayConfirmation(tripId))
            }
            CardPaymentResult.Canceled -> Unit
            is CardPaymentResult.Failed -> AppMessages.show(result.message)
        }
    }
    var givenName by remember { mutableStateOf("") }
    var familyName by remember { mutableStateOf("") }
    var bornOn by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var loyaltyProgramme by remember { mutableStateOf("") }
    var loyaltyNumber by remember { mutableStateOf("") }
    val (amount, currency) = viewModel.stayTotal()

    LaunchedEffect(booking) {
        val b = booking
        if (b is StayBookingState.Success) {
            viewModel.resetStayBooking()
            paidTripId = b.result.id
            paying = cardPayment.present(b.result.clientSecret, b.result.publishableKey)
            if (!paying) AppMessages.show("Payment is unavailable right now. Please try again.")
        }
    }

    com.vayunmathur.library.ui.AppScaffold(
        title = stringResource(R.string.guest_details),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        val loading = booking is StayBookingState.Loading
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.total), style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatMoney(amount, currency),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            OutlinedTextField(givenName, { givenName = it }, label = { Text(stringResource(R.string.given_name)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(familyName, { familyName = it }, label = { Text(stringResource(R.string.family_name)) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.PersonName), modifier = Modifier.fillMaxWidth())
            DateField(stringResource(R.string.date_of_birth), bornOn, onDate = { bornOn = it }, dateFormat = DateString::monthDayYear)
            OutlinedTextField(
                email, { email = it }, label = { Text(stringResource(UiR.string.email)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                phone, { phone = it }, label = { Text(stringResource(R.string.phone_e_g_14155550123)) }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.hotel_loyalty_optional), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    loyaltyProgramme, { loyaltyProgramme = it }, label = { Text(stringResource(R.string.programme)) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    loyaltyNumber, { loyaltyNumber = it }, label = { Text(stringResource(R.string.number)) }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            (booking as? StayBookingState.Error)?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(R.string.secure_checkout_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val loyalty = if (loyaltyNumber.isNotBlank()) {
                        com.vayunmathur.travel.network.StayLoyaltyAccountDto(
                            programmeName = loyaltyProgramme.trim(),
                            accountNumber = loyaltyNumber.trim(),
                        )
                    } else {
                        null
                    }
                    viewModel.bookStay(
                        StayGuestInputDto(givenName, familyName, bornOn, loyaltyProgrammeAccount = loyalty),
                        email,
                        phone,
                    )
                },
                enabled = !loading && !paying && givenName.isNotBlank() && familyName.isNotBlank() && bornOn.isNotBlank() && email.contains("@") && phone.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.booking))
                } else {
                    Text(stringResource(R.string.continue_to_payment))
                }
            }
        }
    }
}
