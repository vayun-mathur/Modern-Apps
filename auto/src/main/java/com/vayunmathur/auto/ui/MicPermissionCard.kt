package com.vayunmathur.auto.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.auto.R
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberPermissionRequest

/**
 * Gates voice-reply retention on the microphone permission.
 *
 * The mic channel itself needs no permission -- chunks are acked and counted
 * regardless -- but retaining a turn for transcription does. Without the
 * grant the head unit still sees a live endpoint while the phone keeps
 * nothing ([MicSourceChannel] fail-closed). Denials report on the shared
 * snackbar host -- never a Toast.
 */
@Composable
fun MicPermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    // Shared helper: a permanently denied request opens app settings instead of
    // dead-ending (the raw RequestPermission contract returned denied silently).
    val requestMicPermission = rememberPermissionRequest(
        Manifest.permission.RECORD_AUDIO,
    ) { granted = it }
    // A grant made in Settings produces no result callback, so re-probe on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SettingsSection(title = stringResource(R.string.mic_headline)) {
                Text(
                    text = stringResource(R.string.mic_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.mic_permission)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (granted) R.string.mic_permission_granted
                                else R.string.mic_permission_missing,
                            ),
                        )
                    },
                    trailingContent = {
                        if (!granted) {
                            Button(onClick = requestMicPermission) {
                                Text(stringResource(R.string.mic_allow))
                            }
                        }
                    },
                )
            }
        }
    }
}
