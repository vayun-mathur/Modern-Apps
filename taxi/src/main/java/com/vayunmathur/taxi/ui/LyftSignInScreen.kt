package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Text
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.network.lyft.LyftAuth
import com.vayunmathur.taxi.network.lyft.LyftAuthResult
import com.vayunmathur.taxi.data.lyft.LyftTokenStore
import kotlinx.coroutines.launch

/**
 * Lyft sign-in, replicating the official app's phone + SMS grant natively
 * (`lyft-re/api-notes.md` §2). Lyft has no request signing and no TLS pinning, so unlike Uber
 * this does not need a WebView.
 */
@Composable
fun LyftSignInScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tokens = remember { LyftTokenStore(context.applicationContext) }
    val sessionUuid = remember { LyftAuth.newSessionUuid() }

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var challenge by remember { mutableStateOf<LyftAuthResult.NeedsChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    fun handle(result: LyftAuthResult, onSuccess: () -> Unit) {
        busy = false
        when (result) {
            is LyftAuthResult.Success -> { error = null; onSuccess() }
            is LyftAuthResult.NeedsChallenge -> {
                challenge = result
                error = result.description
            }
            is LyftAuthResult.NeedsWebChallenge ->
                error = "Lyft wants a web challenge first: ${result.url}"
            is LyftAuthResult.Failed -> error = result.message
        }
    }

    AppScaffold(title = stringResource(R.string.provider_lyft), onNavigateBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (done) {
                Text(stringResource(R.string.signed_in_as, phone))
                return@Column
            }

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text(stringResource(R.string.phone_number)) },
                placeholder = { Text("+15551234567") },
                singleLine = true,
                enabled = !codeSent && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            if (codeSent) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.verification_code)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            challenge?.let { pending ->
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(stringResource(R.string.account_email)) },
                    placeholder = { pending.hint?.let { Text(it) } },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        if (!codeSent) {
                            handle(LyftAuth.requestSmsCode(phone.trim())) {
                                codeSent = true
                            }
                        } else {
                            val result = LyftAuth.verifySmsCode(
                                phoneNumber = phone.trim(),
                                code = code.trim(),
                                sessionUuid = sessionUuid,
                                email = email.trim().takeIf { it.isNotBlank() },
                            )
                            handle(result) {
                                scope.launch {
                                    tokens.save((result as LyftAuthResult.Success).token)
                                    done = true
                                }
                            }
                        }
                    }
                },
                enabled = !busy && phone.isNotBlank() &&
                    (!codeSent || code.isNotBlank()) &&
                    (challenge == null || email.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (codeSent) R.string.verify else R.string.send_code,
                    ),
                )
            }

            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
