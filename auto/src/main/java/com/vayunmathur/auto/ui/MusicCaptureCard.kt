package com.vayunmathur.auto.ui

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.auto.R
import com.vayunmathur.auto.platform.MusicCapturePrefs
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberMessenger

/**
 * Gates car music on two switches: the in-app consent and the system
 * media-projection grant. Both must be on before phone audio reaches the head
 * unit on ch5; either off means fail-closed silence (music stays on the phone
 * speaker, or pauses, exactly as without projection). Denials report on the
 * shared snackbar host -- never a Toast.
 */
@Composable
fun MusicCaptureCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val messenger = rememberMessenger()
    val deniedMessage = stringResource(R.string.music_capture_denied)
    var consented by remember { mutableStateOf(MusicCapturePrefs.isMusicCaptureConsented(context)) }
    var granted by remember { mutableStateOf(MusicCaptureGrant.hasGrant()) }
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            MusicCaptureGrant.storeResult(result.resultCode, result.data!!)
            granted = true
            com.vayunmathur.auto.service.MusicCaptureService.startIfGranted(context)
        } else {
            messenger.show(deniedMessage)
            granted = false
        }
    }
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            SettingsSection(title = stringResource(R.string.music_capture_headline)) {
                Text(
                    text = stringResource(R.string.music_capture_body),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.music_capture_consent),
                    checked = consented,
                    onCheckedChange = {
                        consented = it
                        MusicCapturePrefs.setMusicCaptureConsented(context, it)
                    },
                    supportingText = stringResource(
                        if (consented) R.string.music_capture_consent_on
                        else R.string.music_capture_consent_off,
                    ),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.music_capture_projection)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (granted) R.string.music_capture_projection_granted
                                else R.string.music_capture_projection_missing,
                            ),
                        )
                    },
                    trailingContent = {
                        if (!granted) {
                            Button(onClick = {
                                val manager = context.getSystemService(MediaProjectionManager::class.java)
                                runCatching {
                                    projectionLauncher.launch(manager.createScreenCaptureIntent())
                                }.onFailure {
                                    messenger.show(deniedMessage)
                                }
                            }) {
                                Text(stringResource(R.string.mic_allow))
                            }
                        }
                    },
                )
            }
        }
    }
}
