package com.vayunmathur.communicate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.SmsMessage
import com.vayunmathur.communicate.data.signal.SignalSafetyNumber
import com.vayunmathur.library.ui.EmptyState
import com.vayunmathur.library.ui.IconSms
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun MessagesList(padding: PaddingValues, refresh: Int, load: suspend () -> List<SmsMessage>) {
    val messages = produceState<List<SmsMessage>?>(initialValue = null, refresh) {
        value = withContext(Dispatchers.IO) { load() }
    }
    MessagesContent(padding, messages.value)
}

@Composable
internal fun MessagesContent(padding: PaddingValues, rows: List<SmsMessage>?) {
    when (rows) {
        null -> androidx.compose.foundation.layout.Box(Modifier.padding(padding)) { Text("Loading\u2026") }
        emptyList<SmsMessage>() -> EmptyState(
            title = stringResource(R.string.empty_messages),
            icon = { IconSms() },
            modifier = Modifier.padding(padding),
        )
        else -> LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }
    }
}

@Composable
internal fun SafetyNumberBanner(
    safetyNumber: String,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.signal_safety_number_changed_title),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.signal_safety_number_verify_prompt))
            Spacer(Modifier.height(8.dp))
            Text(
                SignalSafetyNumber.format(safetyNumber),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onAccept) {
                Text(stringResource(R.string.signal_safety_number_accept))
            }
        }
    }
}

/**
 * RCS encryption state banner: locked once the MLS group exists, pending
 * while parked awaiting member keys. Informational only (no action).
 */
@Composable
internal fun EncryptedChatBanner(
    pending: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            stringResource(
                if (pending) R.string.rcs_encrypted_pending else R.string.rcs_encrypted_banner,
            ),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
