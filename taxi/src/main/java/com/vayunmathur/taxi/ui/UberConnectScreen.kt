package com.vayunmathur.taxi.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.taxi.R
import com.vayunmathur.taxi.network.uber.UberWebView

/**
 * Uber sign-in. The user logs in to m.uber.com themselves in a real WebView — Uber's hosted
 * login expects a browser, and this way the app never handles the credentials.
 *
 * While the session is live the injected hook records every GraphQL call the page makes
 * (logcat tag `UberWeb`). That is the discovery step for the fare operations; see
 * `uber-re/api-notes.md` §4.
 */
@Composable
fun UberConnectScreen(onBack: () -> Unit, onTryNative: () -> Unit = {}) {
    var captured by remember { mutableIntStateOf(0) }

    AppScaffold(title = stringResource(R.string.provider_uber), onNavigateBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (captured > 0) {
                Text(
                    text = stringResource(R.string.uber_captured_calls, captured),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            TextButton(
                onClick = onTryNative,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.uber_try_native))
            }
            UberWebView(
                modifier = Modifier.fillMaxSize(),
                onGraphqlCaptured = { captured++ },
            )
        }
    }
}
