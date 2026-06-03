package com.vayunmathur.messages.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import com.vayunmathur.messages.Route
import com.vayunmathur.messages.meta.InstagramClient

@Composable
fun InstagramLoginScreen(backStack: NavBackStack<Route>) {
    val state by InstagramClient.state.collectAsState()
    var cookiesText by remember { mutableStateOf("") }
    var isValidating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state) {
        if (state is InstagramClient.State.Connected) {
            backStack.removeLastOrNull()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Connect Instagram",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "To connect Instagram DMs, you need to extract cookies from your browser after logging in to instagram.com",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Required cookies: sessionid, csrftoken, ds_user_id",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = cookiesText,
            onValueChange = { cookiesText = it },
            label = { Text("Cookies (JSON or key=value pairs)") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 5,
            maxLines = 10,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Format: {\"sessionid\":\"abc\",\"csrftoken\":\"xyz\",...} or sessionid=abc; csrftoken=xyz; ...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (val currentState = state) {
            is InstagramClient.State.NeedsSetup -> {
                Button(
                    onClick = {
                        isValidating = true
                        errorMessage = null
                        val cookies = parseCookies(cookiesText)
                        if (cookies.isNotEmpty() && cookies.containsKey("sessionid")) {
                            InstagramClient.saveAuthData(cookies)
                        } else {
                            errorMessage = "Invalid cookies. Make sure sessionid is present."
                            isValidating = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isValidating && cookiesText.isNotBlank()
                ) {
                    if (isValidating) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text("Connect")
                }
            }

            is InstagramClient.State.Connecting -> {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connecting to Instagram...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is InstagramClient.State.Connected -> {
                Text(
                    text = "Connected successfully!",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            is InstagramClient.State.Disconnected -> {
                Text(
                    text = "Connection failed: ${currentState.reason}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        isValidating = false
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Try Again")
                }
            }

            else -> {
                CircularProgressIndicator()
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "How to get cookies:\n" +
                    "1. Open instagram.com in your browser and log in\n" +
                    "2. Open Developer Tools (F12)\n" +
                    "3. Go to Application/Storage > Cookies\n" +
                    "4. Copy the values for sessionid, csrftoken, ds_user_id, etc.\n" +
                    "5. Paste them here as JSON or key=value pairs",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun parseCookies(input: String): Map<String, String> {
    val trimmed = input.trim()
    return try {
        // Try JSON format first
        if (trimmed.startsWith("{")) {
            val json = kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
                .let { it as kotlinx.serialization.json.JsonObject }
            json.entries.associate { it.key to it.value.toString().trim('"') }
        } else {
            // Parse key=value pairs separated by ; or newline
            trimmed.split(Regex("[;\\n]"))
                .mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else null
                }
                .toMap()
        }
    } catch (e: Exception) {
        emptyMap()
    }
}
