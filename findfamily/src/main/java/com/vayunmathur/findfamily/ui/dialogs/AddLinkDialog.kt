package com.vayunmathur.findfamily.ui.dialogs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
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
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.platform.FindFamilyViewModel
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Composable
fun AddLinkDialog(backStack: NavBackStack<Route>, ffViewModel: FindFamilyViewModel) {
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
    // Links are post-quantum only, so creation can genuinely fail when the PQC keygen is
    // unavailable. Keep the dialog open and say so rather than closing on a link that
    // was never made.
    var failed by remember { mutableStateOf(false) }

    Dialog({backStack.pop()}) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.add_link_title), style = MaterialTheme.typography.headlineMedium)

                OutlinedTextField(name, {name = it}, label = {Text("Title")})

                DropdownField(expiryTime, { expiryTime = it }, options.keys)

                if (failed) {
                    Text(
                        stringResource(R.string.link_pqc_unavailable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Button(
                    {
                        failed = false
                        ffViewModel.createTemporaryLink(name, options[expiryTime]!!) { success ->
                            if (success) backStack.pop() else failed = true
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


