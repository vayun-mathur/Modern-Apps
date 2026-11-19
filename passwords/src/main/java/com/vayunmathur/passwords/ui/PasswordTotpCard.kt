package com.vayunmathur.passwords.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCopy
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.tryOrDefault
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.domain.TOTP

/** TOTP card for the password detail screen: generated code with a countdown ring. */
@Composable
fun PasswordTotpCard(password: Password, now: () -> Long, onCopy: (String) -> Unit) {
    Card(shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.section_totp), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val secret = password.totpSecret
            if (secret.isNullOrBlank()) {
                Text(stringResource(R.string.totp_not_configured))
            } else {
                val timeStep = now() / 1000 / 30
                // Imported/synced secrets may not decode to a key; show placeholder, not a crash.
                val currentCode = remember(secret, timeStep) {
                    tryOrDefault("----") { TOTP.generate(secret, timeStep * 30) }
                }
                val secondsRemaining = ((30000 - now() % 30000) / 1000).toInt()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text(currentCode, style = MaterialTheme.typography.displayMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.totp_refreshes_in, secondsRemaining), style = MaterialTheme.typography.bodySmall)
                    }

                    // Circular progress showing proportion of time remaining
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            { (30000L - now() % 30000L) / 30000f },
                            Modifier.size(56.dp),
                        )
                        IconButton({
                            onCopy(currentCode)
                        }) {
                            IconCopy()
                        }
                    }
                }
            }
        }
    }
}
